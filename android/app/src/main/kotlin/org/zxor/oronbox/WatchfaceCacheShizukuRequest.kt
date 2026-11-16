package org.zxor.oronbox

/** Compatibility for the legacy decoder's jpg spelling of the fixed preview (policy uses png). */
internal object WatchfaceCacheShizukuRequest {
    fun decode(command: String): WatchfaceCacheRequest {
        try {
            return WatchfaceCacheRequest.decode(command)
        } catch (_: IllegalArgumentException) {
            // Try only the single fixed preview below, then compare the entire regenerated program.
        } catch (_: WatchfaceCacheException) {
            // Malformed commands still fail the canonical comparison below.
        }
        val marker = "\np='"
        val offset = command.indexOf(marker)
        if (offset < 0) throw WatchfaceCacheException("INVALID_REQUEST")
        var index = offset + marker.length
        val path = StringBuilder()
        while (index < command.length) {
            if (command[index] != '\'') { path.append(command[index++]); continue }
            if (command.startsWith("'\"'\"'", index)) { path.append('\''); index += 5; continue }
            val fixed = WatchfaceCacheMetadata.FIXED_PREVIEW
            if (!path.endsWith("/$fixed")) break
            val bin = path.toString().removeSuffix(fixed) + "resource.bin"
            val request = WatchfaceCacheRequest("preview", bin, fixed)
            if (runCatching { request.command() == command }.getOrDefault(false)) return request
            break
        }
        throw WatchfaceCacheException("INVALID_REQUEST")
    }
}
