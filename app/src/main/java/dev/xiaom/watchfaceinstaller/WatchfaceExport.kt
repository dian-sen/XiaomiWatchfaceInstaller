package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.IOException

/** create must return a NEW document, never an existing one. Only these handles are cleaned up. */
interface ExportStore {
    fun names(): Set<String>
    fun create(name: String, mime: String): String
    fun name(handle: String): String
    fun write(handle: String, file: File)
    fun delete(handle: String): Boolean
}
data class ExportResult(val message: String, val complete: Boolean)
object WatchfaceExport {
    fun export(store: ExportStore, bin: File, preview: File?, base: String): ExportResult {
        val created = mutableListOf<String>()
        val written = mutableListOf<String>()
        try {
            val ext = preview?.inputStream()?.use { WatchfacePreview.extension(WatchfacePreview.bounded(it)) }
            val extensions = listOfNotNull("bin", ext)
            val stem = ExportNames.available(base, extensions, store.names())
            val files = listOfNotNull(bin, preview)
            extensions.forEachIndexed { index, extension ->
                val name = "$stem.$extension"
                // Refresh before each create; a provider may independently rename on a concurrent conflict.
                if (store.names().any { it.equals(name, true) }) throw IOException("目录内容已变化，请重试（未覆盖）")
                val mime = when (extension) { "png" -> "image/png"; "jpg" -> "image/jpeg"; else -> "application/octet-stream" }
                val handle = store.create(name, mime)
                require(handle !in created) { "文档提供程序未返回新文件" }
                created += handle
                require(store.name(handle) == name) { "文档提供程序改变了文件名；为保证 BIN/预览同名已停止" }
                store.write(handle, files[index])
                written += name
            }
            return ExportResult("已导出：${written.joinToString("、")}" + if (preview == null) "；没有可用预览图，仅导出 BIN" else "（同名 BIN + 预览图）", true)
        } catch (e: Exception) {
            val remaining = created.filter { handle -> !runCatching { store.delete(handle) }.getOrDefault(false) }
            val detail = if (remaining.isEmpty()) "本次新建文件已清理；未覆盖已有文件" else
                "部分结果：${remaining.joinToString { runCatching { store.name(it) }.getOrDefault(it) }} 未能清理，可能不完整，请在所选目录检查"
            return ExportResult("导出失败：${e.message}；$detail", false)
        }
    }
}

/** Validate an internal Activity result before preserving the original XML name/preview.
 * Only ID digits may differ; this is not a format/signature/compatibility certification.
 */
object WorkingCopyResult {
    fun privateFile(root: File, path: String): File {
        val input = File(path)
        val canonicalRoot = root.canonicalFile
        require(input.isAbsolute && input.absoluteFile == input.canonicalFile &&
            input.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) && input != canonicalRoot) {
            "返回文件不在规范的应用私有目录内（不接受符号链接）"
        }
        require(java.nio.file.Files.isRegularFile(input.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            input.length() in 0xa8L..WatchfaceFiles.MAX_BYTES) { "返回文件不是有效大小的私有普通文件" }
        return input
    }
    fun accept(root: File, path: String, original: WatchfaceInfo): WatchfaceInfo {
        val source = privateFile(root, original.file.path)
        val file = privateFile(root, path)
        require(file != source) { "必须返回新建工作副本，不能覆盖原文件" }
        val before = WatchfaceParser.inspect(source)
        require(before.sha256 == original.sha256 && before.id == original.id) { "原工作副本已变化，拒绝沿用名称/预览" }
        val after = WatchfaceParser.inspect(file)
        val oldId = before.id; val newId = after.id
        require(before.format == "XIAOMI_BIN" && after.format == "XIAOMI_BIN" &&
            oldId != null && newId != null && oldId.length == newId.length) { "返回文件头或 ID 长度不受支持" }
        require(source.length() == file.length()) { "返回副本长度变化" }
        source.inputStream().buffered().use { a -> file.inputStream().buffered().use { b ->
            var offset = 0L
            while (true) {
                val x = a.read(); val y = b.read()
                require(x == y || (offset >= 0x28 && offset < 0x28 + oldId!!.length)) { "返回副本除 ID 外存在变化，拒绝沿用名称/预览" }
                if (x == -1) break
                offset++
            }
        } }
        val changed = oldId != newId
        return after.copy(metadata = original.metadata, metadataWarnings = original.metadataWarnings +
            if (changed) listOf("ID 已修改：$oldId → $newId；保留原 XML 名称/预览，XML ID 不再代表当前副本。签名与兼容性未验证。") else emptyList())
    }
}
