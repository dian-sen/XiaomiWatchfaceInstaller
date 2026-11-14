package dev.xiaom.watchfaceinstaller

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class CacheReaderService : ICacheReader.Stub() {
    private val roots = listOf(
        File("/storage/emulated/0/Android/data/com.mi.health/files/WatchFace"),
        File("/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local")
    )

    override fun scan(): String {
        val files = JSONArray()
        val errors = JSONArray()
        val visited = HashSet<String>()
        var examined = 0
        var truncated = false
        fun error(file: File, message: String) {
            if (errors.length() < 100) errors.put("${file.path}: $message")
            else truncated = true
        }
        fun walk(root: File, entry: File, depth: Int) {
            if (examined >= 10000 || files.length() >= 200) { truncated = true; return }
            examined++
            try {
                val file = entry.canonicalFile
                if (file != root && !file.path.startsWith(root.path + File.separator)) {
                    error(entry, "路径越出缓存根目录"); return
                }
                if (!visited.add(file.path)) return
                if (file.isDirectory) {
                    if (depth >= 8) { truncated = true; return }
                    val children = file.listFiles() ?: throw IOException("无法列出目录（权限不足或目录已消失）")
                    for (child in children) {
                        if (examined >= 10000 || files.length() >= 200) { truncated = true; break }
                        walk(root, child, depth + 1)
                    }
                } else if (file.isFile && file.extension.equals("bin", true)) {
                    if (!file.canRead()) throw IOException("文件不可读")
                    if (file.length() > WatchfaceFiles.MAX_BYTES) {
                        error(file, "超过 32 MiB，已跳过"); return
                    }
                    files.put(JSONObject().apply {
                        put("name", file.name); put("path", file.path)
                        put("size", file.length()); put("modified", file.lastModified())
                    })
                } else if (!file.exists()) error(entry, "路径不存在或不可访问")
            } catch (e: IOException) { error(entry, e.message ?: e.javaClass.simpleName) }
            catch (e: SecurityException) { error(entry, e.message ?: "权限不足") }
        }
        for (entry in roots) {
            try {
                val root = entry.canonicalFile
                if (!root.isDirectory) error(entry, "缓存目录不存在或不可访问")
                else walk(root, root, 0)
            } catch (e: IOException) { error(entry, e.message ?: "路径解析失败") }
            catch (e: SecurityException) { error(entry, e.message ?: "权限不足") }
        }
        return JSONObject().apply {
            put("files", files); put("errors", errors); put("truncated", truncated)
        }.toString()
    }

    override fun readMetadata(binPath: String, relative: String): ByteArray? {
        val path = RootPolicy.metadataPath(binPath, relative)
        val file = File(path)
        val root = roots.first { path.startsWith(it.path + "/") }
        // Allow the platform storage alias, but reject symlinks below it.
        var current = File("/storage/emulated/0")
        for (part in path.removePrefix("/storage/emulated/0/").split('/')) {
            current = File(current, part)
            val stat = try { android.system.Os.lstat(current.path) }
            catch (e: android.system.ErrnoException) {
                if (e.errno == android.system.OsConstants.ENOENT) return null
                throw e
            }
            require(!android.system.OsConstants.S_ISLNK(stat.st_mode)) { "拒绝符号链接" }
        }
        val expectedRoot = File("/storage/emulated/0").canonicalPath + "/" + root.path.removePrefix("/storage/emulated/0/")
        require(root.canonicalPath == expectedRoot) { "XML 缓存根目录包含符号链接或已改变" }
        val canonical = file.canonicalPath
        require(canonical.startsWith(expectedRoot + "/")) { "XML canonical 路径越界" }
        val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK, 0)
        try {
            val descriptor = ParcelFileDescriptor.dup(fd)
            descriptor.use {
                require(File(android.system.Os.readlink("/proc/self/fd/${it.fd}")).canonicalPath == canonical) { "XML 打开期间路径改变" }
                val stat = android.system.Os.fstat(fd)
                require(android.system.OsConstants.S_ISREG(stat.st_mode)) { "XML 必须是普通文件" }
                require(stat.st_size in 0..WatchfaceXml.MAX_BYTES.toLong()) { "XML 超过 512 KiB，已跳过" }
                // Duplicate ownership only once; AutoCloseInputStream closes this fd.
                return ParcelFileDescriptor.AutoCloseInputStream(it).use(WatchfaceXml::bounded)
            }
        } finally { android.system.Os.close(fd) }
    }

    private fun verifyFixedPath(path: String): String {
        var current = File("/storage/emulated/0")
        for (part in path.removePrefix("/storage/emulated/0/").split('/')) {
            current = File(current, part)
            require(!android.system.OsConstants.S_ISLNK(android.system.Os.lstat(current.path).st_mode)) { "拒绝缓存符号链接" }
        }
        val expected = File("/storage/emulated/0").canonicalPath + "/" + path.removePrefix("/storage/emulated/0/")
        require(File(path).canonicalPath == expected) { "缓存 canonical 路径改变" }
        return expected
    }

    override fun listPreviews(binPath: String): Array<String> {
        val path = RootPolicy.previewDirectory(binPath)
        val result = mutableListOf<String>()
        val fixed = RootPolicy.previewPath(binPath, WatchfacePreview.FIXED)
        if (File(fixed).exists()) {
            verifyFixedPath(fixed)
            if (java.nio.file.Files.isRegularFile(File(fixed).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) result += WatchfacePreview.FIXED
        }
        if (!File(path).exists()) return result.toTypedArray()
        verifyFixedPath(path)
        // DirectoryStream is bounded: never allocate an unbounded list of cache entries.
        java.nio.file.Files.newDirectoryStream(File(path).toPath()).use { entries ->
            var examined = 0
            for (entry in entries) {
                if (++examined > 1024 || result.size >= 64) break
                val relative = WatchfacePreview.PREFIX + entry.fileName.toString()
                if (WatchfacePreview.relative(relative) != null &&
                    java.nio.file.Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) result += relative
            }
        }
        verifyFixedPath(path)
        return result.sorted().toTypedArray()
    }

    override fun openPreview(binPath: String, relative: String): ParcelFileDescriptor {
        val path = RootPolicy.previewPath(binPath, relative)
        val canonical = verifyFixedPath(path)
        val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK, 0)
        try {
            val stat = android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..WatchfacePreview.MAX_BYTES.toLong()) { "预览必须为不超过 8 MiB 的普通文件" }
            val descriptor = ParcelFileDescriptor.dup(fd)
            try {
                require(File(android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")).canonicalPath == canonical) { "预览打开期间路径改变" }
                verifyFixedPath(path)
                // Validate signature before exposing the read-only descriptor; rewind afterwards.
                val header = ByteArray(12)
                val n = android.system.Os.read(fd, header, 0, header.size)
                WatchfacePreview.extension(header.copyOf(n.coerceAtLeast(0)))
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
                return descriptor
            } catch (e: Exception) { descriptor.close(); throw e }
        } finally { android.system.Os.close(fd) }
    }

    override fun openReadOnly(path: String): ParcelFileDescriptor {
        RootPolicy.validatePath(path)
        val canonical = verifyFixedPath(path)
        val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK, 0)
        try {
            val stat = android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 0..WatchfaceFiles.MAX_BYTES) { "BIN 必须为不超过 32 MiB 的普通文件" }
            val descriptor = ParcelFileDescriptor.dup(fd)
            try {
                require(File(android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")).canonicalPath == canonical) { "BIN 打开期间路径改变" }
                verifyFixedPath(path)
                return descriptor
            } catch (e: Exception) { descriptor.close(); throw e }
        } finally { android.system.Os.close(fd) }
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
