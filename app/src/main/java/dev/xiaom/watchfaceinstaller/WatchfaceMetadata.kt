package dev.xiaom.watchfaceinstaller

import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** Untrusted declarations, never a device compatibility certificate. */
data class WatchfaceMetadata(val name: String, val id: String, val source: String,
    val device: String? = null, val screen: String? = null) {
    val declaration: String get() = listOfNotNull(device, screen).joinToString(" / ") + "（XML 声明，非兼容认证）"
}
data class MetadataResult(val metadata: WatchfaceMetadata? = null, val warnings: List<String> = emptyList())

object WatchfaceXml {
    const val MAX_BYTES = 512 * 1024
    val paths = listOf("description.xml", "resources/manifest.xml")
    fun validId(value: String?) = value?.matches(Regex("(?:[0-9]{9}|[0-9]{12})")) == true
    fun pathId(bin: String): String? = bin.split('/').dropLast(1).lastOrNull { validId(it) }
    fun candidate(path: String) = WatchfaceCatalog.isCandidate(WatchfaceItem("resource.bin", path, 0, 0))
    fun bounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "XML 超过 512 KiB，已跳过" }
        return bytes
    }
    private fun clean(s: String?): String? = s?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 256 && it.none { c -> Character.isISOControl(c) }
    }

    fun parse(bytes: ByteArray, source: String): WatchfaceMetadata? {
        require(bytes.size <= MAX_BYTES) { "XML 超过 512 KiB，已跳过" }
        require(source in paths)
        val description = source == paths[0]
        var id: String? = null; var direct: String? = null; var device: String? = null; var screen: String? = null
        var width: String? = null; var height: String? = null
        val translations = linkedMapOf<String, String>()
        val stack = mutableListOf<String>()
        var translation: String? = null
        val text = StringBuilder()
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("禁止 DTD/实体") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("禁止外部实体") }
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { throw SAXException("禁止外部实体") }
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                stack += q
                require(stack.size <= 32) { "XML 嵌套超限" }
                require(!description || stack.size < 3 || stack[1] !in listOf("name", "pkgName", "deviceType", "size")) { "XML metadata 字段不能嵌套元素" }
                if (stack.size == 1) {
                    require(q == if (description) "watch" else "Watchface") { "XML 根元素不匹配" }
                    if (!description) {
                        id = a.getValue("id"); direct = clean(a.getValue("name"))
                        width = clean(a.getValue("width")); height = clean(a.getValue("height"))
                    }
                }
                if (!description && stack == listOf("Watchface", "Resources", "Translation")) translation = a.getValue("name")
                if (!description && stack == listOf("Watchface", "Resources", "Translation", "Item") && translation == direct?.removePrefix("@")) {
                    val language = a.getValue("language")
                    clean(a.getValue("str"))?.let { if (language in listOf("zh_CN", "zh_TW", "en_US")) translations.putIfAbsent(language, it) }
                }
                text.setLength(0)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                require(text.length + length <= MAX_BYTES) { "XML 文本超限" }
                text.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                if (description && stack.size == 2) when(q) {
                    "name" -> direct = clean(text.toString())
                    "pkgName" -> id = text.toString().trim()
                    "deviceType" -> device = clean(text.toString())
                    "size" -> screen = clean(text.toString())
                }
                if (q == "Translation") translation = null
                stack.removeAt(stack.lastIndex); text.setLength(0)
            }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
        // Required, fail closed if a provider cannot report DTDs (also on Android).
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
        require(validId(id)) { "XML ID 必须为 9 或 12 位数字" }
        val name = if (!description && direct?.startsWith("@") == true) listOf("zh_CN", "zh_TW", "en_US").firstNotNullOfOrNull { translations[it] } else clean(direct)
        if (name == null) return null
        if (!description && width != null && height != null) screen = "${width}x${height}"
        return WatchfaceMetadata(name, id!!, source, device, screen)
    }

    /** Only fixed siblings of candidate resource.bin; read callback returns null for missing files. */
    fun load(bin: String, expectedId: String? = pathId(bin), identity: String = "缓存路径",
        read: (String) -> ByteArray?): MetadataResult {
        if (!candidate(bin)) return MetadataResult()
        val warnings = mutableListOf<String>()
        for (source in paths) {
            if (Thread.currentThread().isInterrupted) throw java.io.IOException("XML 读取已取消")
            try {
                val bytes = read(source) ?: continue
                val metadata = parse(bytes, source) ?: continue
                if (metadata.id != expectedId) {
                    warnings += "警告：$source XML ID ${metadata.id} 与 $identity ID ${expectedId ?: "未识别"} 不匹配，不使用 XML 名称"
                    continue
                }
                return MetadataResult(metadata, warnings)
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw e
                warnings += "$source 已跳过：${e.message?.take(160)}"
            }
        }
        return MetadataResult(warnings = warnings)
    }
    fun verify(metadata: WatchfaceMetadata?, binId: String?): MetadataResult = when {
        metadata == null -> MetadataResult()
        metadata.id != binId -> MetadataResult(warnings = listOf("警告：XML ID ${metadata.id} 与 BIN ID ${binId ?: "未识别"} 不匹配，不使用 XML 名称"))
        else -> MetadataResult(metadata)
    }
}
