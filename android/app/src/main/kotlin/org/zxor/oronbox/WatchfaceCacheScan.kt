package org.zxor.oronbox

internal data class WatchfaceCacheEntry(
    val token: String,
    val name: String,
    val location: String,
    val size: Long,
    val modifiedAt: Long,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "token" to token, "name" to name, "location" to location,
        "size" to size, "modifiedAt" to modifiedAt,
    )
}

internal data class WatchfaceCacheDiagnostic(val code: String, val path: String, val reason: String) {
    fun toMap(): Map<String, String> = mapOf("code" to code, "path" to path, "reason" to reason)
}

internal data class WatchfaceCacheScan(
    val files: List<WatchfaceCacheEntry>,
    val truncated: Boolean,
    val diagnostics: List<WatchfaceCacheDiagnostic> = emptyList(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "files" to files.map(WatchfaceCacheEntry::toMap),
        "truncated" to truncated,
        "diagnostics" to diagnostics.map(WatchfaceCacheDiagnostic::toMap),
    )
}

internal data class WatchfaceCacheSource(val path: String, val size: Long, val modifiedAt: Long)
internal data class WatchfaceCacheRecords(
    val files: List<WatchfaceCacheSource>,
    val diagnostics: List<WatchfaceCacheDiagnostic>,
    val truncated: Boolean,
)

/** Validate the full protocol before replacing the token registry. */
internal object WatchfaceCacheScanProtocol {
    fun parse(bytes: ByteArray): WatchfaceCacheRecords {
        if (bytes.size > WatchfaceCacheRootPolicy.MAX_SCAN_BYTES) fail()
        val text = WatchfaceCacheRootShell.utf8(bytes)
        if (!text.endsWith('\u0000')) fail()
        val fields = text.dropLast(1).split('\u0000')
        if (fields.size % 4 != 0) fail()
        val files = mutableListOf<WatchfaceCacheSource>()
        val diagnostics = mutableListOf<WatchfaceCacheDiagnostic>()
        val seen = mutableSetOf<String>()
        var done = false
        var truncated = false
        for ((kind, path, a, b) in fields.chunked(4)) {
            if (done) fail()
            if (kind == "DONE") {
                if (path.isNotEmpty() || a.isNotEmpty() || b.isNotEmpty()) fail()
                done = true
                continue
            }
            try {
                if (path !in WatchfaceCacheRootPolicy.roots) WatchfaceCacheRootPolicy.rootFor(path)
            } catch (_: IllegalArgumentException) { fail() }
            if (kind == "FILE") {
                try { WatchfaceCacheRootPolicy.validateBin(path) } catch (_: IllegalArgumentException) { fail() }
                val size = a.toLongOrNull() ?: fail()
                val seconds = b.toLongOrNull() ?: fail()
                if (!seen.add(path) || files.size >= WatchfaceCacheRootPolicy.MAX_FILES ||
                    size !in 0..WatchfaceCacheRootPolicy.MAX_BIN_BYTES || seconds !in 0..Long.MAX_VALUE / 1000
                ) fail()
                files += WatchfaceCacheSource(path, size, seconds * 1000)
            } else {
                if (kind !in setOf("MISSING", "DENIED", "LINK", "ESCAPE", "NOT_REGULAR", "LIMIT", "IO") ||
                    a !in setOf("root", "directory", "file", "parent", "canonical", "stat", "vanished", "skipped",
                        "enumeration", "entries", "files", "depth", "size", "path", "output", "diagnostics") ||
                    b != "0" || diagnostics.size > WatchfaceCacheRootPolicy.MAX_DIAGNOSTICS
                ) fail()
                diagnostics += WatchfaceCacheDiagnostic(kind, path, a)
                if (kind == "LIMIT") truncated = true
            }
        }
        if (!done) fail()
        return WatchfaceCacheRecords(files, diagnostics, truncated)
    }

    private fun fail(): Nothing = throw WatchfaceCacheException("INVALID_SCAN_RESPONSE")
}
