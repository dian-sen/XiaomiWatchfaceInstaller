package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WatchfaceXmlTest {
    private val id = "120917345196"
    private val bin = RootPolicy.roots.first() + "/$id/resource.bin"
    private fun description(name: String = "流沙 2", pkg: String = id) =
        "<watch><name>$name</name><deviceType>N67</deviceType><size>336x480</size><pkgName>$pkg</pkgName></watch>".toByteArray()
    private fun manifest(name: String, items: String = "") =
        "<Watchface name=\"$name\" id=\"$id\" width=\"336\" height=\"480\"><Resources><Translation name=\"watchfaceName\">$items</Translation></Resources></Watchface>".toByteArray()
    private fun parse(bytes: ByteArray, source: String = "description.xml") = WatchfaceXml.parse(bytes, source)!!

    @Test fun realDescriptionAndDeclarations() {
        val meta = parse(description())
        assertEquals("流沙 2", meta.name); assertEquals(id, meta.id)
        assertEquals("N67", meta.device); assertEquals("336x480", meta.screen)
        assertTrue(meta.declaration.contains("非兼容认证"))
    }
    @Test fun directManifestAndReferencePriorityIndependentOfOrder() {
        assertEquals("怪兽动态", parse(manifest("怪兽动态"), "resources/manifest.xml").name)
        val meta = parse(manifest("@watchfaceName", """
            <Item language="en_US" str="Pastel clouds 2"/><Item language="zh_TW" str="繁體"/>
            <Item language="zh_CN" str="流沙 2"/>
        """), "resources/manifest.xml")
        assertEquals("流沙 2", meta.name); assertEquals("336x480", meta.screen)
    }
    @Test fun translationFallbackAndUnresolvedReference() {
        for ((items, expected) in listOf("<Item language='zh_TW' str='繁體'/><Item language='en_US' str='English'/>" to "繁體",
            "<Item language='en_US' str='English'/>" to "English")) {
            assertEquals(expected, parse(manifest("@watchfaceName", items), "resources/manifest.xml").name)
        }
        assertNull(WatchfaceXml.parse(manifest("@missing", "<Item language='zh_CN' str='错误'/>"), "resources/manifest.xml"))
    }
    @Test fun descriptionFirstAndOnlyFixedPaths() {
        val called = mutableListOf<String>()
        val result = WatchfaceXml.load(bin) { called += it; description() }
        assertEquals(listOf("description.xml"), called); assertEquals("流沙 2", result.metadata?.name)
        val fallback = WatchfaceXml.load(bin) { if (it == "description.xml") null else manifest("怪兽动态") }
        assertEquals("resources/manifest.xml", fallback.metadata?.source)
        WatchfaceXml.load(bin.replace("resource.bin", "other.bin")) { fail("non candidate must not read XML"); null }
        WatchfaceXml.load(bin.replace("/$id/", "/$id/resources/")) { fail("resource must not read XML"); null }
    }
    @Test fun idsAreNineOrTwelveDigitsOnly() {
        assertEquals("123456789", parse(description(pkg = "123456789")).id)
        for (bad in listOf("", "123", "1234567890", "1234567890123", "１２３４５６７８９", "abcdefghi")) {
            assertThrows(IllegalArgumentException::class.java) { parse(description(pkg = bad)) }
        }
    }
    @Test fun pathAndSelectedBinConflictsNeverUseXmlName() {
        val mismatch = WatchfaceXml.load(bin) { description(pkg = "123456789") }
        assertNull(mismatch.metadata); assertTrue(mismatch.warnings.isNotEmpty())
        val noPathId = WatchfaceXml.load(bin.replace("/$id/", "/unknown/")) { description() }
        assertNull(noPathId.metadata)
        val meta = parse(description())
        for (headerId in listOf(null, "123456789")) {
            val result = WatchfaceXml.verify(meta, headerId)
            assertNull(result.metadata); assertTrue(result.warnings.single().contains("不匹配"))
        }
        assertEquals(meta, WatchfaceXml.verify(meta, id).metadata)
    }
    @Test fun rejectsDtdInternalExternalAndParameterEntities() {
        for (declaration in listOf(
            "<!DOCTYPE watch [<!ENTITY evil 'exploded'>]>",
            "<!DOCTYPE watch SYSTEM 'file:///etc/passwd'>",
            "<!DOCTYPE watch [<!ENTITY evil SYSTEM 'http://127.0.0.1:9/'>]>",
            "<!DOCTYPE watch [<!ENTITY % evil SYSTEM 'file:///etc/passwd'>%evil;]>")) {
            assertThrows(Exception::class.java) { parse(declaration.toByteArray() + description()) }
            val utf16 = ("<?xml version='1.0' encoding='UTF-16'?>" + declaration + description().toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_16)
            assertThrows(Exception::class.java) { parse(utf16) }
        }
    }
    @Test fun rejectsOversizeInputWithExplicitSkipAndBoundedRead() {
        val bytes = ByteArray(WatchfaceXml.MAX_BYTES + 1)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(bytes) }.message!!.contains("512 KiB"))
        val stream = ByteArrayInputStream(bytes + ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) { WatchfaceXml.bounded(stream) }
        assertEquals(100, stream.available())
        val result = WatchfaceXml.load(bin) { bytes }
        assertNull(result.metadata); assertTrue(result.warnings.all { it.contains("已跳过") })
        val exact = description() + " ".repeat(WatchfaceXml.MAX_BYTES - description().size).toByteArray()
        assertEquals("流沙 2", parse(exact).name)
    }
    @Test fun predefinedEntitiesAndNumericNewlineAreNotShellOrDisplayInjection() {
        assertEquals("A & B", parse(description("A &amp; B")).name)
        assertNull(WatchfaceXml.parse(description("A&#10;B"), "description.xml"))
        assertNull(WatchfaceXml.parse(description("A&#13;B"), "description.xml"))
        assertNull(WatchfaceXml.parse(manifest("A&#10;B"), "resources/manifest.xml"))
        assertThrows(Exception::class.java) { parse(description("&unknown;")) }
    }
    @Test fun rejectsMalformedWrongRootAndDeepXml() {
        assertThrows(Exception::class.java) { parse("<watch>".toByteArray()) }
        assertThrows(Exception::class.java) { parse("<unrelated/>".toByteArray()) }
        assertThrows(Exception::class.java) { parse(("<watch>" + "<a>".repeat(33) + "</a>".repeat(33) + "</watch>").toByteArray()) }
    }
    @Test fun catalogSearchIncludesXmlNameButNotRejectedMetadata() {
        val item = WatchfaceItem("resource.bin", bin, 0, 0, parse(description()))
        assertEquals(listOf(item), WatchfaceCatalog.filter(listOf(item), BinFilter.CANDIDATE, "流沙"))
        assertTrue(WatchfaceCatalog.filter(listOf(item.copy(metadata = null)), BinFilter.ALL, "流沙").isEmpty())
    }
    @Test fun selectedFileUsesBinIdRatherThanStalePathId() {
        val noPath = bin.replace("/$id/", "/unknown/")
        assertEquals("流沙 2", WatchfaceXml.load(noPath, id, "BIN") { description() }.metadata?.name)
        val mismatch = WatchfaceXml.load(bin, "123456789", "BIN") { if (it == "description.xml") description() else null }
        assertNull(mismatch.metadata)
        assertTrue(mismatch.warnings.all { it.contains("BIN ID 123456789") && it.contains("不匹配") })
    }
    @Test fun metadataDoesNotRecoverOrOverwriteCapturedBinaryName() {
        val file = java.io.File.createTempFile("metadata-", ".bin")
        try {
            val bytes = ByteArray(0xa8)
            byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(bytes)
            id.toByteArray().copyInto(bytes, 0x28)
            byteArrayOf(-1, -1, -1, -1).copyInto(bytes, 0x68)
            file.writeBytes(bytes)
            val info = WatchfaceParser.inspect(file)
            val xml = WatchfaceXml.load(bin, info.id, "BIN") { description() }
            val combined = info.copy(metadata = xml.metadata)
            assertNull(combined.name); assertEquals("流沙 2", combined.metadata?.name)
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun conflictingDescriptionFallsBackToMatchingManifest() {
        val result = WatchfaceXml.load(bin) { source ->
            if (source == "description.xml") description(pkg = "123456789") else manifest("怪兽动态")
        }
        assertEquals("怪兽动态", result.metadata?.name)
        assertEquals("resources/manifest.xml", result.metadata?.source)
        assertTrue(result.warnings.single().contains("不匹配"))
    }
    @Test fun descriptionNameIsLiteralEvenWhenStartingWithAtSign() {
        assertEquals("@流沙", parse(description("@流沙")).name)
    }

}
