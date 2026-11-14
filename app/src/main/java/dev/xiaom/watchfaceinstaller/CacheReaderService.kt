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

    override fun openReadOnly(path: String): ParcelFileDescriptor {
        val file = File(path).canonicalFile
        require(roots.any { file.path.startsWith(it.canonicalPath + File.separator) }) { "缓存目录之外的路径" }
        require(file.isFile && file.extension.equals("bin", true)) { "只允许读取 BIN 普通文件" }
        require(file.length() <= WatchfaceFiles.MAX_BYTES) { "文件超过 32 MiB" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptor ->
            if (descriptor.statSize !in 0..WatchfaceFiles.MAX_BYTES) {
                descriptor.close()
                throw IOException("文件大小无效或超过 32 MiB")
            }
        }
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
