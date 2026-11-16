package org.zxor.oronbox

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal data class WatchfaceCacheBinHeader(val id: String?, val name: String?)
internal data class WatchfaceCacheXml(val id: String, val name: String?, val previews: List<String>)

/**
 * XML/name handling adapted from dian-sen/XiaomiWatchfaceInstaller WatchfaceMetadata.kt,
 * WatchfaceParser.kt and WatchfacePreview.kt. https://github.com/dian-sen/XiaomiWatchfaceInstaller
 * XML is an untrusted declaration; the reader binds it to the copied BIN's ID before use.
 */
internal object WatchfaceCacheMetadata {
    const val MAX_XML_BYTES = 512 * 1024
    const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    val XML_PATHS = listOf("description.xml", "resources/manifest.xml")
    const val FIXED_PREVIEW = "preview/preview.png"
    private const val PREVIEW_PREFIX = "resources/_preview/"
    private val magic = byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12)

    fun validId(value: String?): Boolean = value?.matches(Regex("(?:[0-9]{9}|[0-9]{12})")) == true
    fun pathId(bin: String): String? = bin.substringBeforeLast('/').split('/').lastOrNull(::validId)

    fun cleanName(value: String?): String? = value?.trim()?.takeIf { text ->
        text.isNotEmpty() && text.length <= 256 && text.none {
            it == '\uFFFD' || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
        }
    }

    fun binHeader(bytes: ByteArray): WatchfaceCacheBinHeader {
        if (bytes.size < 0xA8 || !magic.indices.all { bytes[it] == magic[it] }) {
            throw WatchfaceCacheException("INVALID_BIN")
        }
        val rawId = field(bytes, 0x28, 64)
        val id = if (rawId.all { it in 0x30..0x39 }) {
            rawId.toString(Charsets.US_ASCII).takeIf(::validId)
        } else null
        val rawName = field(bytes, 0x68, 60)
        var name: String? = null
        for (encoding in listOf("UTF-8", "GB18030")) {
            val charset = try { Charset.forName(encoding) } catch (_: IllegalArgumentException) { continue }
            val decoded = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawName)).toString()
            } catch (_: CharacterCodingException) { continue }
            if (!decoded.toByteArray(charset).contentEquals(rawName)) continue
            name = cleanName(decoded)
            break
        }
        return WatchfaceCacheBinHeader(id, name)
    }

    private fun field(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: (offset + length)
        return bytes.copyOfRange(offset, end)
    }

    fun previewRelative(value: String): String? {
        if (value == FIXED_PREVIEW) return value
        val path = if (value.startsWith("_preview/")) "resources/$value" else value
        if (!path.startsWith(PREVIEW_PREFIX)) return null
        val name = path.removePrefix(PREVIEW_PREFIX)
        if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 240 || name.any {
                it == '/' || it == '\\' || Character.isISOControl(it)
            }) return null
        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in listOf("png", "jpg", "jpeg")) return null
        return path
    }

    fun previewExtension(bytes: ByteArray): String? = when {
        bytes.size !in 1..MAX_PREVIEW_BYTES -> null
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10,
        )) -> "png"
        bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() -> "jpg"
        else -> null
    }

    fun parseXml(bytes: ByteArray, source: String): WatchfaceCacheXml {
        require(source in XML_PATHS && bytes.size <= MAX_XML_BYTES)
        val description = source == XML_PATHS[0]
        var id: String? = null
        var directName: String? = null
        var translation: String? = null
        var nodes = 0
        val translations = linkedMapOf<String, String>()
        val images = linkedMapOf<String, String>()
        val themes = mutableListOf<String>()
        val seenFields = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        val text = StringBuilder()
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("DTD_FORBIDDEN")
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                throw SAXException("ENTITY_FORBIDDEN")
            }
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
                throw SAXException("ENTITY_FORBIDDEN")
            }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                stack += qName
                require(++nodes <= 4096 && stack.size <= 32 && attributes.length <= 64)
                if (stack.size == 1) {
                    require(qName == if (description) "watch" else "Watchface")
                    if (!description) {
                        id = attributes.getValue("id")
                        directName = cleanName(attributes.getValue("name"))
                    }
                }
                if (description && stack.size >= 2 && stack[1] in listOf("name", "pkgName")) {
                    require(stack.size == 2 && seenFields.add(stack[1]))
                }
                if (!description && stack == listOf("Watchface", "Resources", "Translation")) {
                    translation = attributes.getValue("name")
                }
                if (!description && stack == listOf("Watchface", "Resources", "Translation", "Item") &&
                    translation == directName?.takeIf { it.startsWith('@') }?.drop(1)
                ) {
                    val language = attributes.getValue("language")
                    cleanName(attributes.getValue("str"))?.let {
                        if (language in listOf("zh_CN", "zh_TW", "en_US")) translations.putIfAbsent(language, it)
                    }
                }
                if (!description && stack == listOf("Watchface", "Resources", "Image") && images.size < 256) {
                    val key = cleanName(attributes.getValue("name"))
                    val src = cleanName(attributes.getValue("src"))
                    if (key != null && src != null) images.putIfAbsent(key, src)
                }
                if (!description && stack == listOf("Watchface", "Theme") && themes.size < 64) {
                    cleanName(attributes.getValue("preview"))?.let(themes::add)
                }
                text.setLength(0)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                require(text.length + length <= MAX_XML_BYTES)
                text.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (description && stack.size == 2) when (qName) {
                    "name" -> directName = cleanName(text.toString())
                    "pkgName" -> id = text.toString().trim()
                }
                if (qName == "Translation") translation = null
                stack.removeAt(stack.lastIndex)
                text.setLength(0)
            }
            override fun error(error: SAXParseException) { throw error }
            override fun fatalError(error: SAXParseException) { throw error }
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        // Required: fail closed if the provider cannot report DTDs (including on Android).
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
        require(validId(id))
        val name = if (!description && directName?.startsWith('@') == true) {
            listOf("zh_CN", "zh_TW", "en_US").firstNotNullOfOrNull(translations::get)
        } else cleanName(directName)
        val refs = if (description) emptyList() else (themes.map {
            if (it.startsWith('@')) images[it.drop(1)] else it
        } + images.values).filterNotNull().mapNotNull(::previewRelative).distinct().take(64)
        return WatchfaceCacheXml(id!!, name, refs)
    }
}
