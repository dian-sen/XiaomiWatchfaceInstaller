package org.zxor.oronbox

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Owns only copies created by this instance; never adopts or deletes arbitrary cache files. */
internal class OpenedResourceFiles(
    private val cacheDir: File,
    private val maxBinBytes: Long = MAX_BIN_BYTES,
    private val maxOtherBytes: Long = MAX_OTHER_BYTES,
) : AutoCloseable {
    init {
        require(maxBinBytes >= 0 && maxOtherBytes >= 0)
    }
    private val lock = Any()
    private val ownedPaths = linkedMapOf<String, PrivateCacheFileIdentity>()
    private var openDirectory: File? = null
    private var openDirectoryIdentity: PrivateCacheFileIdentity? = null
    private var createdOpenDirectory = false
    private var closed = false

    /** Consumes and closes [source], enforcing a format-specific limit and rolling back partial copies. */
    @Throws(IOException::class)
    fun copy(source: InputStream, requestedName: String?): String {
        var createdPath: String? = null
        try {
            return source.use { input ->
                synchronized(lock) {
                    if (closed) throw IOException("Opened resource cache is closed")
                    val directory = ensureDirectory()
                    val prefix = "${UUID.randomUUID()}__"
                    val suffix = safeBasename(requestedName, MAX_BASENAME_BYTES - utf8Length(prefix))
                    val destination = File(directory, prefix + suffix)
                    if (!destination.createNewFile()) throw IOException("Unable to create unique imported file")
                    val path = destination.absolutePath
                    createdPath = path
                    ownedPaths[path] = PrivateCacheFileIdentity.read(destination)
                    try {
                        val limit = maxBytesForName(suffix, maxBinBytes, maxOtherBytes)
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) {
                                    val value = input.read()
                                    if (value < 0) break
                                    if (total >= limit) throw IOException("Opened file exceeds the size limit")
                                    output.write(value)
                                    total++
                                } else {
                                    if (count > limit || total > limit - count) {
                                        throw IOException("Opened file exceeds the size limit")
                                    }
                                    output.write(buffer, 0, count)
                                    total += count
                                }
                            }
                        }
                        if (!isCanonicalOwnedFile(destination, directory) ||
                            PrivateCacheFileIdentity.read(destination) != ownedPaths[path]
                        ) {
                            throw IOException("Imported file path changed during copy")
                        }
                        path
                    } catch (error: Exception) {
                        removeOwned(path, directory)
                        createdPath = null
                        if (error is IOException) throw error
                        throw IOException("Unable to copy imported file", error)
                    }
                }
            }.also { createdPath = null }
        } catch (error: Exception) {
            createdPath?.let { path -> synchronized(lock) { openDirectory?.let { removeOwned(path, it) } } }
            if (error is IOException) throw error
            throw IOException("Unable to copy imported file", error)
        }
    }

    /** Returns false for unknown paths, changed links, or already-released files. */
    fun release(path: String?): Boolean = synchronized(lock) {
        if (closed || path == null || path !in ownedPaths) return@synchronized false
        val directory = openDirectory ?: return@synchronized false
        try {
            removeOwned(path, directory)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Removes registered files only; pre-existing/unregistered files are never adopted. */
    fun cleanupOwned(): Int = synchronized(lock) { cleanupOwnedLocked() }

    override fun close() {
        synchronized(lock) {
            closed = true
            cleanupOwnedLocked()
        }
    }

    private fun ensureDirectory(): File {
        openDirectory?.let { directory ->
            if (!isSafeDirectory(directory)) throw IOException("Imported file directory path changed")
            return directory
        }
        val cache = cacheDir.canonicalFile
        if (!cache.isDirectory) throw IOException("App cache directory is unavailable")
        val expected = File(cache, DIRECTORY_NAME)
        if (expected.absoluteFile != expected.canonicalFile && expected.exists()) {
            throw IOException("Imported file directory contains a symbolic link")
        }
        if (!expected.exists()) {
            if (!expected.mkdir()) throw IOException("Unable to create imported file directory")
            createdOpenDirectory = true
        }
        if (!expected.isDirectory || expected.absoluteFile != expected.canonicalFile ||
            expected.parentFile?.canonicalFile != cache
        ) {
            throw IOException("Imported file directory path changed")
        }
        openDirectoryIdentity = PrivateCacheFileIdentity.read(expected, directory = true)
        openDirectory = expected
        return expected
    }

    private fun cleanupOwnedLocked(): Int {
        val directory = openDirectory ?: return 0
        var deleted = 0
        ownedPaths.keys.toList().forEach { path ->
            try {
                if (removeOwned(path, directory)) deleted++
            } catch (_: IOException) {
                Unit
            } catch (_: SecurityException) {
                Unit
            }
        }
        try { removeCreatedDirectoryIfEmpty(directory) } catch (_: IOException) { Unit }
        return deleted
    }

    private fun removeOwned(path: String, directory: File): Boolean {
        if (path !in ownedPaths || !isCanonicalOwnedFile(File(path), directory)) return false
        val file = File(path)
        val identity = PrivateCacheFileIdentity.readIfPresent(file)
        if (identity != null && (identity != ownedPaths[path] || !file.delete())) return false
        ownedPaths.remove(path)
        removeCreatedDirectoryIfEmpty(directory)
        return true
    }

    private fun removeCreatedDirectoryIfEmpty(directory: File) {
        if (createdOpenDirectory && ownedPaths.isEmpty() && isSafeDirectory(directory) &&
            directory.list()?.isEmpty() == true && directory.delete()
        ) {
            openDirectory = null
            openDirectoryIdentity = null
            createdOpenDirectory = false
        }
    }

    private fun isCanonicalOwnedFile(file: File, directory: File): Boolean {
        if (file.absolutePath != file.canonicalPath || !isSafeDirectory(directory)) return false
        return file.parentFile == directory && directory.parentFile?.canonicalFile == cacheDir.canonicalFile
    }

    private fun isSafeDirectory(directory: File): Boolean =
        directory.isDirectory && directory.absoluteFile == directory.canonicalFile &&
            directory.parentFile?.canonicalFile == cacheDir.canonicalFile &&
            PrivateCacheFileIdentity.read(directory, directory = true) == openDirectoryIdentity

    companion object {
        private const val DIRECTORY_NAME = "file_open"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_BASENAME_BYTES = 180
        private const val MAX_BIN_BYTES = 32L * 1024 * 1024
        private const val MAX_OTHER_BYTES = 128L * 1024 * 1024
        private val boundedExtension = Regex("\\.[A-Za-z0-9]{1,16}$")

        fun maxBytesForName(
            name: String?,
            maxBinBytes: Long = MAX_BIN_BYTES,
            maxOtherBytes: Long = MAX_OTHER_BYTES,
        ): Long = if (name?.substringAfterLast('.', "")?.equals("bin", ignoreCase = true) == true) {
            maxBinBytes
        } else {
            maxOtherBytes
        }

        /** Sanitizes one basename and truncates on code-point boundaries while retaining a safe suffix. */
        fun safeBasename(requestedName: String?, maxUtf8Bytes: Int = MAX_BASENAME_BYTES): String {
            require(maxUtf8Bytes > 0)
            val leaf = requestedName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val extension = boundedExtension.find(leaf)?.value.orEmpty()
            val rawStem = if (extension.isEmpty()) leaf else leaf.dropLast(extension.length)
            val stem = sanitize(rawStem).trim().trim('.').trim().ifEmpty { "opened-file" }
            val safeExtension = extension.takeIf { ext ->
                ext.drop(1).all { it.isLetterOrDigit() && it.code < 128 }
            }.orEmpty()
            val extensionBytes = utf8Length(safeExtension)
            val usableExtension = if (extensionBytes < maxUtf8Bytes) safeExtension else ""
            val safeStem = truncateUtf8(stem, maxUtf8Bytes - utf8Length(usableExtension))
                .trim().trim('.').ifEmpty { truncateUtf8("opened-file", maxUtf8Bytes) }
            val result = safeStem + usableExtension
            return if (utf8Length(result) <= maxUtf8Bytes) result else truncateUtf8(result, maxUtf8Bytes)
        }

        private fun sanitize(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val charCount = Character.charCount(codePoint)
                val invalid = codePoint == 0 || Character.isISOControl(codePoint) ||
                    Character.getType(codePoint) == Character.FORMAT.toInt() ||
                    codePoint in 0xD800..0xDFFF ||
                    codePoint in intArrayOf('<'.code, '>'.code, ':'.code, '"'.code, '/'.code,
                        '\\'.code, '|'.code, '?'.code, '*'.code)
                append(if (invalid) '_' else String(Character.toChars(codePoint)))
                index += charCount
            }
        }

        private fun truncateUtf8(value: String, limit: Int): String {
            val output = StringBuilder()
            var used = 0
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val width = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (used + width > limit) break
                output.appendCodePoint(codePoint)
                used += width
                index += Character.charCount(codePoint)
            }
            return output.toString()
        }

        private fun utf8Length(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size
    }
}
