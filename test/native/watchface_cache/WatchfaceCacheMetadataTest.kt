package org.zxor.oronbox

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class WatchfaceCacheMetadataTest {
    private val id = "120917345196"
    private fun bin(name: ByteArray = "BIN 名称".toByteArray(), rawId: String = id): ByteArray = ByteArray(0xA8).apply {
        byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(this)
        rawId.toByteArray().copyInto(this, 0x28)
        name.copyInto(this, 0x68)
    }
    private fun description(name: String = "流沙 2", pkg: String = id) =
        "<watch><name>$name</name><pkgName>$pkg</pkgName></watch>".toByteArray()

    @Test fun parsesHeaderIdAndStrictUtf8Name() {
        assertEquals(WatchfaceCacheBinHeader(id, "BIN 名称"), WatchfaceCacheMetadata.binHeader(bin()))
        assertNull(WatchfaceCacheMetadata.binHeader(bin(rawId = "12345abc9")).id)
        assertNull(WatchfaceCacheMetadata.binHeader(bin(rawId = "1234567890")).id)
        assertEquals("123456789", WatchfaceCacheMetadata.binHeader(bin(rawId = "123456789")).id)
    }

    @Test fun strictLegacyFallbackAndBinaryFieldsNeverBecomeReplacementNames() {
        assertEquals("中文名称", WatchfaceCacheMetadata.binHeader(bin("中文名称".toByteArray(Charset.forName("GB18030")))).name)
        for (raw in listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0xc3.toByte()), "A\nB".toByteArray(), "�".toByteArray())) {
            assertNull(WatchfaceCacheMetadata.binHeader(bin(raw)).name)
        }
    }

    @Test fun incompleteOrUnknownBinIsRejected() {
        for (bytes in listOf(ByteArray(0), bin().copyOf(0x34), ByteArray(0xA8))) {
            assertThrows(WatchfaceCacheException::class.java) { WatchfaceCacheMetadata.binHeader(bytes) }
        }
    }

    @Test fun descriptionAndLocalizedManifestResolveOnlySupportedNameFields() {
        assertEquals(WatchfaceCacheXml(id, "流沙 2", emptyList()), WatchfaceCacheMetadata.parseXml(description(), "description.xml"))
        val manifest = """<Watchface id="$id" name="@title"><Resources>
            <Translation name="other"><Item language="zh_CN" str="错误"/></Translation>
            <Translation name="title"><Item language="en_US" str="English"/><Item language="zh_TW" str="繁體"/>
            <Item language="zh_CN" str="简体"/></Translation></Resources></Watchface>""".toByteArray()
        assertEquals("简体", WatchfaceCacheMetadata.parseXml(manifest, "resources/manifest.xml").name)
        assertNull(WatchfaceCacheMetadata.parseXml("<Watchface id='$id' name='@missing'/>".toByteArray(), "resources/manifest.xml").name)
    }

    @Test fun manifestPreviewRefsCannotExpandTheFixedWhitelist() {
        val manifest = """<Watchface id="$id" name="name"><Theme preview="@best"/><Resources>
            <Image name="best" src="_preview/b.JPG"/><Image name="other" src="resources/_preview/a.png"/>
            <Image name="escape" src="../../outside.png"/><Image name="nested" src="_preview/sub/a.jpg"/>
            <Image name="fixed" src="preview/preview.png"/></Resources></Watchface>""".toByteArray()
        assertEquals(listOf("resources/_preview/b.JPG", "resources/_preview/a.png", "preview/preview.png"),
            WatchfaceCacheMetadata.parseXml(manifest, "resources/manifest.xml").previews)
        for (relative in listOf("/etc/a.png", "_preview/../a.png", "resources/_preview/../a.png", "resources/_preview/a.gif",
            "resources/_preview/a\\b.jpg", "resources/_preview/a\nb.png", "resources/_preview/" + "中".repeat(90) + ".png")) {
            assertNull(relative, WatchfaceCacheMetadata.previewRelative(relative))
        }
    }

    @Test fun rejectsDtdInternalAndExternalEntitiesInUtf8AndUtf16() {
        val cases = listOf(
            "<!DOCTYPE watch [<!ENTITY x 'injected'>]><watch><name>&x;</name><pkgName>$id</pkgName></watch>",
            "<!DOCTYPE watch SYSTEM 'file:///etc/passwd'><watch><name>A</name><pkgName>$id</pkgName></watch>",
            "<!DOCTYPE watch [<!ENTITY % x SYSTEM 'https://invalid.example/entity'>%x;]><watch/>",
        )
        for (xml in cases) {
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
                assertThrows(Exception::class.java) { WatchfaceCacheMetadata.parseXml(xml.toByteArray(charset), "description.xml") }
            }
        }
    }

    @Test fun xmlBytesDepthNodesDuplicateAndNestedFieldsAreBounded() {
        val exact = description() + ByteArray(WatchfaceCacheMetadata.MAX_XML_BYTES - description().size) { 32 }
        assertEquals("流沙 2", WatchfaceCacheMetadata.parseXml(exact, "description.xml").name)
        val invalid = listOf(
            exact + 32.toByte(), description(pkg = "invalid"), "<notwatch/>".toByteArray(),
            "<watch><name>one</name><name>two</name><pkgName>$id</pkgName></watch>".toByteArray(),
            description("A<x/>B"),
            ("<watch>" + "<x>".repeat(40) + "</x>".repeat(40) + "</watch>").toByteArray(),
            ("<watch>" + "<x/>".repeat(4100) + "</watch>").toByteArray(),
        )
        for (bytes in invalid) assertThrows(Exception::class.java) { WatchfaceCacheMetadata.parseXml(bytes, "description.xml") }
        assertNull(WatchfaceCacheMetadata.parseXml(description("A&#10;B"), "description.xml").name)
    }

    @Test fun imageSignatureIsCheckedWithoutDecodingUntrustedBitmaps() {
        assertEquals("png", WatchfaceCacheMetadata.previewExtension(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("jpg", WatchfaceCacheMetadata.previewExtension(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte())))
        assertNull(WatchfaceCacheMetadata.previewExtension("not an image".toByteArray()))
        assertNull(WatchfaceCacheMetadata.previewExtension(ByteArray(0)))
    }
}
