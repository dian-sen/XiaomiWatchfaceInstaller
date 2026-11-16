package org.zxor.oronbox

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Read-only, bounded access to the two Xiaomi Health cache roots.
 * Call scan/copy/cleanup on the platform channel's single IO executor.
 * Selection uses fresh opaque scan tokens; only diagnostics expose whitelisted source paths.
 */
internal class RootWatchfaceCacheReader(
    private val cacheDir: File,
    private val shell: WatchfaceCacheRootShell = WatchfaceCacheRootShell(),
    private val readOverride: ((String, Long, Long) -> ByteArray)? = null,
) : Closeable {
    private data class Session(
        val directory: File,
        val identity: PrivateCacheFileIdentity,
        val files: MutableMap<File, PrivateCacheFileIdentity> = linkedMapOf(),
    )
    private class ReadBudget {
        private val deadline = System.nanoTime() + 45_000_000_000L
        fun remainingMillis(): Long {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw WatchfaceCacheException("BUNDLE_TIMEOUT")
            return remaining
        }
    }
    private val sources = linkedMapOf<String, WatchfaceCacheSource>()
    private val sessions = linkedMapOf<String, Session>()
    private var privateBaseIdentity: PrivateCacheFileIdentity? = null
    private var closed = false

    /** Each scan invalidates all previous selection tokens, including when scanning fails. */
    fun scan(): WatchfaceCacheScan {
        ensureOpen()
        sources.clear()
        val result = WatchfaceCacheScanProtocol.parse(
            read(WatchfaceCacheRootPolicy.scanCommand(), WatchfaceCacheRootPolicy.MAX_SCAN_BYTES),
        )
        val files = result.files.filter { it.path.substringAfterLast('/') == "resource.bin" }.map { source ->
            val token = UUID.randomUUID().toString()
            sources[token] = source
            val root = WatchfaceCacheRootPolicy.validateBin(source.path)
            WatchfaceCacheEntry(
                token, source.path.substringAfterLast('/'),
                source.path.removePrefix("$root/").substringBeforeLast('/', ""),
                source.size, source.modifiedAt,
            )
        }
        return WatchfaceCacheScan(files, result.truncated, result.diagnostics)
    }

    /**
     * Returns path/name/embeddedId/previews/warnings/nameSource (only codec-safe scalar/maps/lists).
     * Optional XML/images cannot prevent a valid BIN copy; their failures become stable warning codes.
     */
    fun copyBundle(token: String): Map<String, Any?> {
        ensureOpen()
        val source = sources[token] ?: throw WatchfaceCacheException("INVALID_TOKEN")
        if (sessions.size >= MAX_SESSIONS) throw WatchfaceCacheException("PRIVATE_SESSION_LIMIT")
        val budget = ReadBudget()
        val bytes = read(WatchfaceCacheRootPolicy.readBinCommand(source.path), WatchfaceCacheRootPolicy.MAX_BIN_BYTES, budget.remainingMillis())
        if (bytes.size.toLong() != source.size) throw WatchfaceCacheException("SOURCE_CHANGED")
        val header = WatchfaceCacheMetadata.binHeader(bytes)
        val warnings = linkedSetOf<String>()
        if (header.id == null) warnings += "BIN_ID_UNRECOGNIZED"
        val pathId = WatchfaceCacheMetadata.pathId(source.path)
        if (pathId != null && pathId != header.id) warnings += "PATH_ID_MISMATCH"
        var xmlName: String? = null
        var manifestRefs = emptyList<String>()
        val candidate = WatchfaceCacheRootPolicy.candidate(source.path)
        if (candidate) {
            for (relative in WatchfaceCacheMetadata.XML_PATHS) {
                val prefix = if (relative == "description.xml") "DESCRIPTION" else "MANIFEST"
                val xmlBytes = optionalRead(prefix, warnings) {
                    read(WatchfaceCacheRootPolicy.metadataCommand(source.path, relative), WatchfaceCacheMetadata.MAX_XML_BYTES.toLong(), budget.remainingMillis())
                } ?: continue
                val xml = try {
                    WatchfaceCacheMetadata.parseXml(xmlBytes, relative)
                } catch (error: Exception) {
                    checkCancellation(error)
                    warnings += "${prefix}_INVALID_XML"
                    continue
                }
                if (header.id == null || xml.id != header.id || (pathId != null && xml.id != pathId)) {
                    warnings += "${prefix}_ID_MISMATCH"
                    continue
                }
                if (xmlName == null) xmlName = xml.name
                if (relative == "resources/manifest.xml") manifestRefs = xml.previews
            }
        }
        val nameSource = when {
            xmlName != null -> "xml"
            header.name != null -> "bin"
            else -> "filename"
        }
        val displayName = xmlName ?: header.name ?: WatchfaceCacheMetadata.cleanName(
            source.path.substringAfterLast('/').substringBeforeLast('.'),
        ) ?: "watchface"
        if (nameSource == "filename") warnings += "NAME_FILENAME_FALLBACK"
        val session = createSession()
        val bin = File(session.directory, "watchface.bin")
        try {
            save(session, bin, bytes)
            val previews = if (candidate) copyPreviews(session, source.path, manifestRefs, warnings, budget) else emptyList()
            if (previews.isEmpty()) warnings += "NO_PREVIEW"
            checkCancellation()
            return mapOf(
                "path" to bin.absolutePath,
                "name" to displayName,
                "embeddedId" to header.id,
                "previews" to previews,
                "warnings" to warnings.toList(),
                "nameSource" to nameSource,
            )
        } catch (error: Exception) {
            try { cleanupSession(session) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** Only an exact BIN path returned by this reader instance can identify a session. */
    fun cleanupBundle(path: String) {
        val session = sessions[path] ?: throw WatchfaceCacheException("UNOWNED_BUNDLE")
        cleanupSession(session)
    }

    /** Attempt every registered session even when one was replaced or is otherwise unsafe to delete. */
    fun cleanupAll() {
        var failure: IOException? = null
        sessions.values.toList().forEach { session ->
            try {
                cleanupSession(session)
            } catch (error: IOException) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /** Run on the owning executor, after any interrupted operation has unwound. No root command is run. */
    override fun close() {
        closed = true
        sources.clear()
        cleanupAll()
    }

    private fun ensureOpen() {
        if (closed) throw WatchfaceCacheException("READER_CLOSED")
    }

    private fun read(command: String, limit: Long, budgetMillis: Long? = null): ByteArray =
        readOverride?.invoke(command, limit, budgetMillis ?: 20_000)
            ?: if (budgetMillis == null) shell.read(command, limit) else shell.read(command, limit, budgetMillis)

    // Keep the old bridge source-compatible while MainActivity migrates to bundle responses.
    fun copyToPrivateCache(token: String): String = copyBundle(token).getValue("path") as String
    fun deletePrivateCopy(path: String) = cleanupBundle(path)

    private fun copyPreviews(
        session: Session,
        bin: String,
        manifestRefs: List<String>,
        warnings: MutableSet<String>,
        budget: ReadBudget,
    ): List<Map<String, String>> {
        val listed = optionalRead("PREVIEW_LIST", warnings) {
            read(WatchfaceCacheRootPolicy.previewListCommand(bin), 32L * 1024, budget.remainingMillis())
        }
        val fallback = mutableListOf<String>()
        if (listed != null) {
            try {
                val text = WatchfaceCacheRootShell.utf8(listed)
                val records = if (text.endsWith('\u0000')) text.dropLast(1).split('\u0000') else emptyList()
                if (records.lastOrNull() != "!DONE" || records.size > WatchfaceCacheRootPolicy.MAX_PREVIEW_VISITED + 2) {
                    throw WatchfaceCacheException("INVALID_PREVIEW_RESPONSE")
                }
                for (relative in records.dropLast(1)) {
                    when (relative) {
                        "!LIMIT" -> warnings += "PREVIEW_LIST_LIMIT"
                        "!LINK" -> warnings += "PREVIEW_LIST_LINK"
                        "!IO" -> warnings += "PREVIEW_LIST_IO"
                        "!DENIED" -> warnings += "PREVIEW_LIST_DENIED"
                        "!MISSING" -> warnings += "PREVIEW_LIST_MISSING"
                        else -> {
                            if (WatchfaceCacheMetadata.previewRelative(relative) != relative) {
                                throw WatchfaceCacheException("INVALID_PREVIEW_RESPONSE")
                            }
                            fallback += relative
                        }
                    }
                }
            } catch (error: Exception) {
                checkCancellation(error)
                fallback.clear()
                warnings += "PREVIEW_LIST_INVALID"
            }
        }
        val candidates = (manifestRefs + WatchfaceCacheMetadata.FIXED_PREVIEW + fallback.sorted()).distinct()
        if (candidates.size > WatchfaceCacheRootPolicy.MAX_PREVIEW_CANDIDATES) warnings += "PREVIEW_CANDIDATE_LIMIT"
        val previews = mutableListOf<Map<String, String>>()
        var totalBytes = 0L
        for (relative in candidates.take(WatchfaceCacheRootPolicy.MAX_PREVIEW_CANDIDATES)) {
            if (previews.size >= MAX_PREVIEWS || totalBytes >= MAX_PREVIEW_TOTAL_BYTES) {
                warnings += "PREVIEW_BUNDLE_LIMIT"
                break
            }
            try { budget.remainingMillis() } catch (_: WatchfaceCacheException) {
                warnings += "PREVIEW_BUNDLE_TIMEOUT"
                break
            }
            val bytes = optionalRead("PREVIEW", warnings) {
                read(WatchfaceCacheRootPolicy.previewCommand(bin, relative), WatchfaceCacheMetadata.MAX_PREVIEW_BYTES.toLong(), budget.remainingMillis())
            } ?: continue
            val extension = WatchfaceCacheMetadata.previewExtension(bytes)
            if (extension == null) {
                warnings += "PREVIEW_INVALID_IMAGE"
                continue
            }
            if (totalBytes + bytes.size > MAX_PREVIEW_TOTAL_BYTES) {
                warnings += "PREVIEW_BUNDLE_LIMIT"
                continue
            }
            val copy = File(session.directory, "preview-${previews.size}.$extension")
            save(session, copy, bytes)
            previews += mapOf("path" to copy.absolutePath, "name" to relative.substringAfterLast('/'))
            totalBytes += bytes.size
        }
        return previews
    }

    private fun optionalRead(prefix: String, warnings: MutableSet<String>, read: () -> ByteArray): ByteArray? = try {
        checkCancellation()
        read()
    } catch (error: IOException) {
        checkCancellation(error)
        val code = (error as? WatchfaceCacheException)?.code ?: "IO"
        // Missing optional siblings are normal. Missing roots still surface in scan diagnostics.
        if (code != "MISSING") warnings += "${prefix}_$code"
        null
    }

    private fun createSession(): Session {
        val base = privateBase(create = true)
        repeat(8) {
            val directory = File(base, "session-${UUID.randomUUID()}")
            if (directory.mkdir()) {
                verifyDirect(directory, base)
                val session = Session(directory, PrivateCacheFileIdentity.read(directory, directory = true))
                sessions[File(directory, "watchface.bin").absolutePath] = session
                return session
            }
        }
        throw WatchfaceCacheException("PRIVATE_CACHE_IO")
    }

    private fun save(session: Session, file: File, bytes: ByteArray) {
        verifySession(session)
        verifyDirect(file, session.directory)
        if (!file.createNewFile()) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
        val identity = PrivateCacheFileIdentity.read(file)
        session.files[file] = identity
        try {
            file.outputStream().use { it.write(bytes) }
            verifySession(session)
            verifyDirect(file, session.directory)
            if (PrivateCacheFileIdentity.read(file) != identity) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
        } catch (error: IOException) {
            if (error is WatchfaceCacheException) throw error
            throw WatchfaceCacheException("PRIVATE_CACHE_IO", cause = error)
        }
    }

    private fun cleanupSession(session: Session) {
        val key = File(session.directory, "watchface.bin").absolutePath
        if (sessions[key] !== session) throw WatchfaceCacheException("UNOWNED_BUNDLE")
        verifySession(session)
        // Never recurse or delete unregistered children, even in an owned directory.
        session.files.toList().forEach { (file, identity) ->
            verifySession(session)
            verifyDirect(file, session.directory)
            val current = try {
                PrivateCacheFileIdentity.readIfPresent(file)
            } catch (error: IOException) {
                throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED", cause = error)
            }
            if (current != null) {
                if (current != identity) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
                if (!file.delete()) throw WatchfaceCacheException("PRIVATE_CACHE_IO")
            }
            session.files.remove(file)
        }
        verifySession(session)
        if (!session.directory.delete()) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
        sessions.remove(key)
    }

    private fun verifySession(session: Session) {
        val base = privateBase(create = false)
        verifyDirect(session.directory, base)
        if (!session.directory.isDirectory ||
            PrivateCacheFileIdentity.read(session.directory, directory = true) != session.identity
        ) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
    }

    private fun privateBase(create: Boolean): File {
        // cacheDir may be Android's trusted /data/user/0 alias. Below its canonical root no links are allowed.
        val cache = cacheDir.canonicalFile
        if (!cache.isDirectory) throw WatchfaceCacheException("PRIVATE_CACHE_IO")
        val base = File(cache, "watchface_cache_import")
        verifyDirect(base, cache)
        if (create && !base.isDirectory && !base.mkdir()) throw WatchfaceCacheException("PRIVATE_CACHE_IO")
        if (!base.isDirectory) throw WatchfaceCacheException("PRIVATE_CACHE_IO")
        val identity = PrivateCacheFileIdentity.read(base, directory = true)
        val registered = privateBaseIdentity
        if (registered != null && registered != identity) throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
        privateBaseIdentity = identity
        return base
    }

    private fun verifyDirect(file: File, parent: File) {
        if (file.parentFile != parent || file.absoluteFile != file.canonicalFile) {
            throw WatchfaceCacheException("PRIVATE_CACHE_CHANGED")
        }
    }

    private fun checkCancellation(error: Exception? = null) {
        if (Thread.currentThread().isInterrupted || (error is WatchfaceCacheException && error.code == "CANCELLED")) {
            throw WatchfaceCacheException("CANCELLED", cause = error)
        }
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val MAX_PREVIEWS = 8
        private const val MAX_PREVIEW_TOTAL_BYTES = 16L * 1024 * 1024
    }
}
