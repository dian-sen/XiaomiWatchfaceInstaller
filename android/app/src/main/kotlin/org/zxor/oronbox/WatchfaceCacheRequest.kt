package org.zxor.oronbox

/** Typed transport boundary. A remote backend never accepts a shell program from Flutter/Binder. */
internal data class WatchfaceCacheRequest(val kind: String, val bin: String = "", val relative: String = "") {
    fun command(): String = when (kind) {
        "scan" -> { require(bin.isEmpty() && relative.isEmpty()); WatchfaceCacheRootPolicy.scanCommand() }
        "bin" -> { require(relative.isEmpty()); WatchfaceCacheRootPolicy.readBinCommand(bin) }
        "metadata" -> WatchfaceCacheRootPolicy.metadataCommand(bin, relative)
        "preview" -> WatchfaceCacheRootPolicy.previewCommand(bin, relative)
        "previews" -> { require(relative.isEmpty()); WatchfaceCacheRootPolicy.previewListCommand(bin) }
        else -> throw IllegalArgumentException("Unknown cache request")
    }

    fun limit(): Long {
        command() // Validate the complete request, including relative paths.
        return when (kind) {
            "scan" -> WatchfaceCacheRootPolicy.MAX_SCAN_BYTES
            "bin" -> WatchfaceCacheRootPolicy.MAX_BIN_BYTES
            "metadata" -> WatchfaceCacheMetadata.MAX_XML_BYTES.toLong()
            "preview" -> WatchfaceCacheMetadata.MAX_PREVIEW_BYTES.toLong()
            else -> 32L * 1024
        }
    }

    companion object {
        // Only the UID guard changes. All traversal, descriptor pinning and byte limits stay identical.
        fun forUid(command: String, uid: Int): String {
            require(uid == 0 || uid == 2000)
            val guard = "[ \"\$uid\" = 0 ] || exit 77"
            require(command.contains(guard))
            return command.replace(guard, "[ \"\$uid\" = $uid ] || exit 77")
        }

        /** Decode our own canonical commands, then regenerate and compare the entire program. */
        fun decode(command: String): WatchfaceCacheRequest {
            if (command == WatchfaceCacheRootPolicy.scanCommand()) return WatchfaceCacheRequest("scan")
            val offset = command.indexOf("\np='")
            val directory = command.indexOf("\nd='")
            if (offset >= 0) {
                val path = quoted(command, offset + 3)
                val root = WatchfaceCacheRootPolicy.rootFor(path)
                val requests = mutableListOf<WatchfaceCacheRequest>()
                if (path.endsWith(".bin", true)) requests += WatchfaceCacheRequest("bin", path)
                // The optional sibling has a fixed candidate basename. It is never opened itself here.
                for (relative in WatchfaceCacheMetadata.XML_PATHS) {
                    if (path.endsWith("/$relative")) requests += WatchfaceCacheRequest(
                        "metadata", path.removeSuffix(relative) + "resource.bin", relative,
                    )
                }
                val marker = "/resources/_preview/"
                val markerAt = path.lastIndexOf(marker)
                if (markerAt > root.length) requests += WatchfaceCacheRequest(
                    "preview", path.substring(0, markerAt) + "/resource.bin", path.substring(markerAt + 1),
                )
                val fixed = WatchfaceCacheMetadata.FIXED_PREVIEW
                if (path.endsWith("/$fixed")) requests += WatchfaceCacheRequest(
                    "preview", path.removeSuffix(fixed) + "resource.bin", fixed,
                )
                return requests.firstOrNull { runCatching { it.command() == command }.getOrDefault(false) }
                    ?: throw WatchfaceCacheException("INVALID_REQUEST")
            }
            if (directory >= 0) {
                val path = quoted(command, directory + 3)
                require(path.endsWith("/resources/_preview"))
                val request = WatchfaceCacheRequest("previews", path.removeSuffix("resources/_preview") + "resource.bin")
                if (request.command() == command) return request
            }
            throw WatchfaceCacheException("INVALID_REQUEST")
        }

        private fun quoted(text: String, start: Int): String {
            require(text[start] == '\'')
            val value = StringBuilder()
            var index = start + 1
            while (index < text.length) {
                if (text[index] != '\'') { value.append(text[index++]); continue }
                if (text.startsWith("'\"'\"'", index)) { value.append('\''); index += 5; continue }
                return value.toString()
            }
            throw WatchfaceCacheException("INVALID_REQUEST")
        }
    }
}
