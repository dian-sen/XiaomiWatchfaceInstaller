package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Fixed sibling preview/preview.png or direct PNG/JPEG children of resources/_preview. */
object WatchfacePreview {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val PREFIX = "resources/_preview/"
    const val FIXED = "preview/preview.png"
    fun relative(value: String): String? {
        if (value == FIXED) return FIXED
        val path = if (value.startsWith("_preview/")) "resources/$value" else value
        if (!path.startsWith(PREFIX)) return null
        val name = path.removePrefix(PREFIX)
        if (name.isBlank() || name == "." || name == ".." || name.toByteArray().size > 240 ||
            name.any { it == '/' || it == '\\' || Character.isISOControl(it) }) return null
        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in listOf("png", "jpg", "jpeg")) return null
        return path
    }
    fun bounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MAX_BYTES) {
            if (Thread.currentThread().isInterrupted) throw java.io.IOException("预览读取已取消")
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (n < 0) break
            if (n > 0) out.write(buffer, 0, n)
        }
        require(out.size() in 1..MAX_BYTES) { "预览为空或超过 8 MiB" }
        return out.toByteArray()
    }
    fun extension(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_BYTES) { "预览大小无效" }
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        return when {
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            starts(255, 216, 255) -> "jpg"
            else -> throw IllegalArgumentException("预览不是 PNG/JPEG（签名不符）")
        }
    }
    fun save(directory: File, bytes: ByteArray): File {
        val ext = extension(bytes)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建私有预览目录" }
        val file = File.createTempFile("preview-", ".$ext", directory)
        try { file.outputStream().use { it.write(bytes) }; return file }
        catch (e: Exception) { file.delete(); throw e }
    }

    /** XML preference then a bounded, deterministic fallback; no arbitrary XML path is opened. */
    fun load(directory: File, id: String?, manifest: () -> ByteArray?, list: () -> List<String>,
        read: (String) -> ByteArray): PreviewResult {
        val warnings = mutableListOf<String>()
        val preferred = mutableListOf<String>()
        try {
            manifest()?.let {
                val metadata = WatchfaceXml.parse(it, "resources/manifest.xml", preferred)
                if (id == null || metadata?.id != id) { preferred.clear(); warnings += "预览 XML ID 不匹配或名称无法解析，使用固定目录候选" }
            }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            warnings += "预览 XML 已跳过：${e.message?.take(100)}"
        }
        val fallback = try { list().mapNotNull(::relative).distinct().sorted().take(64) }
        catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            warnings += "预览目录不可读：${e.message?.take(100)}"; emptyList()
        }
        for (path in (preferred + listOf(FIXED) + fallback).distinct().take(64)) {
            try { return PreviewResult(save(directory, read(path)), warnings) }
            catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw e
                if (warnings.size < 5) warnings += "预览已跳过：${path.substringAfterLast('/')}：${e.message?.take(100)}"
            }
        }
        return PreviewResult(null, warnings + "没有可用预览图；仍可只导出 BIN")
    }
}
data class PreviewResult(val file: File?, val warnings: List<String>)

object ExportNames {
    /** UTF-8 byte budget leaves room for extensions and numeric conflict suffixes. */
    fun safeBase(value: String?): String {
        val clean = (value ?: "").map { c ->
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() || c in "/\\:*?\"<>|") '_' else c
        }.joinToString("").trim().trim('.').trim()
        val output = StringBuilder()
        var bytes = 0
        clean.codePoints().forEachOrdered { cp ->
            val part = String(Character.toChars(cp))
            if (bytes + part.toByteArray(Charsets.UTF_8).size <= 160) {
                output.append(part); bytes += part.toByteArray(Charsets.UTF_8).size
            } else bytes = 161
        }
        var result = output.toString().trim().trimEnd('.').ifBlank { "watchface" }
        if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(?:\\..*)?$").matches(result)) result = "_$result"
        return result
    }
    fun forInfo(info: WatchfaceInfo, idEdited: Boolean = false): String {
        val name = info.metadata?.name ?: info.name ?: info.id ?: "watchface"
        // Sanitize/truncate the name BEFORE adding ID so a long XML name never hides the new ID.
        return safeBase(name) + if (idEdited && WatchfaceXml.validId(info.id)) "_ID-${info.id}" else ""
    }
    fun available(base: String, extensions: List<String>, existing: Set<String>): String {
        val names = existing.map { it.lowercase(Locale.ROOT) }.toSet()
        for (i in 0..9999) {
            val suffix = Regex("_ID-(?:[0-9]{12}|[0-9]{9})$").find(base)?.value.orEmpty()
            val candidate = safeBase(base.removeSuffix(suffix)) + suffix + if (i == 0) "" else " ($i)"
            if (extensions.none { "$candidate.$it".lowercase(Locale.ROOT) in names }) return candidate
        }
        throw java.io.IOException("同名文件过多，未导出")
    }
}
