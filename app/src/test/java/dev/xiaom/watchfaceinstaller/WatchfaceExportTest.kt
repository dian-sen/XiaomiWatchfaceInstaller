package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WatchfaceExportTest {
    @get:Rule val temp = TemporaryFolder()
    private val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
    private fun bin() = temp.newFile().apply { writeText("BIN") }
    private fun image() = temp.newFile().apply { writeBytes(png) }
    private class Store : ExportStore {
        val content = linkedMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var failWrite = 0; var writes = 0; var failDelete = false; var rename = false
        override fun names() = content.keys.toSet()
        override fun create(name: String, mime: String): String {
            val actual = if (rename) "renamed-$name" else name
            check(actual !in content); content[actual] = "partial"; return actual
        }
        override fun name(handle: String) = handle
        override fun write(handle: String, file: File) {
            if (++writes == failWrite) throw IOException("injected write error")
            content[handle] = "complete"
        }
        override fun delete(handle: String): Boolean {
            deleted += handle
            return !failDelete && content.remove(handle) != null
        }
    }
    @Test fun namesPreserveChineseAndRemoveUnsafeCharacters() {
        assertEquals("中文表盘", ExportNames.safeBase(" 中文表盘. "))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k", ExportNames.safeBase("a/b\\c:d*e?f\"g<h>i|j\nk"))
        assertEquals("watchface", ExportNames.safeBase("..."))
        for (name in listOf("CON", "nul.txt", "LPT1", "com9")) assertTrue(ExportNames.safeBase(name).startsWith('_'))
        assertTrue(ExportNames.safeBase("中😀".repeat(200)).toByteArray().size <= 160)
        assertFalse(ExportNames.safeBase("😀".repeat(200)).contains('\uFFFD'))
    }
    @Test fun collisionsUseCommonSuffixCaseInsensitive() {
        assertEquals("中文 (2)", ExportNames.available("中文", listOf("bin", "png"), setOf("中文.BIN", "中文 (1).png")))
        val store = Store().apply { content["中文.bin"] = "original" }
        val result = WatchfaceExport.export(store, bin(), image(), "中文")
        assertTrue(result.complete)
        assertEquals(setOf("中文.bin", "中文 (1).bin", "中文 (1).png"), store.content.keys)
        assertEquals("original", store.content["中文.bin"])
    }
    @Test fun missingPreviewStillExportsBinWithNotice() {
        val store = Store()
        val result = WatchfaceExport.export(store, bin(), null, "test")
        assertTrue(result.complete); assertTrue(result.message.contains("仅导出 BIN"))
        assertEquals(setOf("test.bin"), store.content.keys)
    }
    @Test fun partialFailureCleansOnlyNewFiles() {
        val store = Store().apply { failWrite = 2; content["keep.bin"] = "original" }
        val result = WatchfaceExport.export(store, bin(), image(), "test")
        assertFalse(result.complete)
        assertEquals(setOf("keep.bin"), store.content.keys)
        assertEquals(listOf("test.bin", "test.png"), store.deleted)
    }
    @Test fun cleanupFailureExplicitlyReportsPartialResults() {
        val store = Store().apply { failWrite = 2; failDelete = true }
        val result = WatchfaceExport.export(store, bin(), image(), "test")
        assertFalse(result.complete); assertTrue(result.message.contains("部分结果"))
        assertTrue(result.message.contains("test.bin")); assertTrue(result.message.contains("test.png"))
    }
    @Test fun providerRenamingDoesNotProduceMismatchedPair() {
        val store = Store().apply { rename = true }
        assertFalse(WatchfaceExport.export(store, bin(), image(), "test").complete)
        assertTrue(store.content.isEmpty()); assertEquals(0, store.writes)
    }
    @Test fun invalidSignatureCreatesNoOutput() {
        val store = Store()
        val result = WatchfaceExport.export(store, bin(), bin(), "test")
        assertFalse(result.complete); assertTrue(store.content.isEmpty())
    }
    @Test fun signaturesAndEightMiBBoundary() {
        assertEquals("png", WatchfacePreview.extension(png))
        assertEquals("jpg", WatchfacePreview.extension(byteArrayOf(-1,-40,-1,0)))
        assertThrows(IllegalArgumentException::class.java) { WatchfacePreview.extension("RIFFxxxxWEBP".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { WatchfacePreview.extension("not png".toByteArray()) }
        assertEquals(WatchfacePreview.MAX_BYTES, WatchfacePreview.bounded(ByteArray(WatchfacePreview.MAX_BYTES).inputStream()).size)
        assertThrows(IllegalArgumentException::class.java) { WatchfacePreview.bounded(ByteArray(WatchfacePreview.MAX_BYTES + 1).inputStream()) }
    }
    @Test fun previewPathsAreDirectChildrenOnly() {
        assertEquals("resources/_preview/中文.png", WatchfacePreview.relative("_preview/中文.png"))
        for (value in listOf("/etc/a.png", "resources/_preview/../a.png", "resources/_preview/sub/a.png", "resources/_preview/a\\b.png", "resources/_preview/a\n.png", "resources/a.png", "resources/_preview/a.bin")) {
            assertNull(value, WatchfacePreview.relative(value))
        }
        val bin = RootPolicy.roots.first() + "/resource.bin"
        assertThrows(IllegalArgumentException::class.java) { RootPolicy.previewPath(bin, "_preview/a.png") }
        assertThrows(IllegalArgumentException::class.java) { RootPolicy.previewDirectory(bin.replace("resource.bin", "other.bin")) }
    }
    private fun manifest(id: String = "120917345196") = """<Watchface id="$id" name="中文"><Resources>
        <Image name="other" src="_preview/other.png"/><Image name="chosen" src="_preview/chosen.png"/>
        <Image name="bad" src="../../secret.png"/></Resources><Theme preview="@chosen"/></Watchface>""".toByteArray()
    @Test fun themeReferenceTakesPriorityOverImagesAndFallback() {
        val paths = mutableListOf<String>()
        WatchfaceXml.parse(manifest(), "resources/manifest.xml", paths)
        assertEquals(listOf("resources/_preview/chosen.png", "resources/_preview/other.png"), paths)
        val reads = mutableListOf<String>()
        val result = WatchfacePreview.load(temp.newFolder(), "120917345196", { manifest() }, { listOf("resources/_preview/fallback.png") }) {
            reads += it; png
        }
        assertEquals(listOf("resources/_preview/chosen.png"), reads)
        assertArrayEquals(png, result.file!!.readBytes())
    }
    @Test fun badXmlOrMissingPreferredFallsBackToFixedDirectory() {
        val result = WatchfacePreview.load(temp.newFolder(), "120917345196", { manifest("999999999") },
            { listOf("/secret.png", "resources/_preview/fallback.png") }) { path ->
            if (path == WatchfacePreview.FIXED) throw IOException("missing fixed preview")
            assertEquals("resources/_preview/fallback.png", path); png
        }
        assertNotNull(result.file); assertTrue(result.warnings.isNotEmpty())
    }
    @Test fun missingImagesReturnClearBinOnlyResult() {
        val result = WatchfacePreview.load(temp.newFolder(), "120917345196", { manifest() }, { emptyList() }) { throw IOException("missing") }
        assertNull(result.file); assertTrue(result.warnings.last().contains("只导出 BIN"))
    }
    @Test fun rootPreviewReadRejectsSymlinksAndOversize() {
        val storage = temp.newFolder("storage")
        val bin = RootPolicy.roots.first() + "/resource.bin"
        fun mapped(path: String) = File(path.replace("/storage/emulated/0", storage.path))
        val shell = RootShell(5000) { command -> ProcessBuilder("sh", "-c", command.replace("/storage/emulated/0", storage.path)).start() }
        val image = mapped(RootPolicy.previewPath(bin, "resources/_preview/中文.png"))
        image.parentFile.mkdirs(); image.writeBytes(png)
        assertArrayEquals(png, shell.read(RootPolicy.previewCommand(bin, "resources/_preview/中文.png"), WatchfacePreview.MAX_BYTES.toLong(), WatchfacePreview::bounded))
        assertEquals("resources/_preview/中文.png\u0000", shell.text(RootPolicy.previewListCommand(bin), 65536))
        image.delete(); Files.createSymbolicLink(image.toPath(), bin().toPath())
        assertThrows(IOException::class.java) { shell.text(RootPolicy.previewCommand(bin, "resources/_preview/中文.png"), 65536) }
        image.delete(); java.io.RandomAccessFile(image, "rw").use { it.setLength(WatchfacePreview.MAX_BYTES + 1L) }
        assertThrows(IOException::class.java) { shell.text(RootPolicy.previewCommand(bin, "resources/_preview/中文.png"), 65536) }
    }

    @Test fun realFixedPreviewIsWhitelistedAndIndependentOfMissingResourceDirectory() {
        assertEquals("preview/preview.png", WatchfacePreview.relative("preview/preview.png"))
        for (bad in listOf("preview/other.png", "preview/preview.jpg", "preview/../preview.png", "resources/_preview/sub/a.png", "resources/_preview/a.webp")) assertNull(WatchfacePreview.relative(bad))
        val bin = RootPolicy.roots.first() + "/resource.bin"
        assertEquals(bin.substringBeforeLast('/') + "/preview/preview.png", RootPolicy.previewPath(bin, WatchfacePreview.FIXED))
        val reads = mutableListOf<String>()
        val result = WatchfacePreview.load(temp.newFolder(), "120917345196", { null }, { throw IOException("no _preview") }) {
            reads += it; png
        }
        assertEquals(listOf(WatchfacePreview.FIXED), reads)
        assertNotNull(result.file)
    }
    @Test fun fixedXmlReferenceRequiresMatchingBinId() {
        val xml = """<Watchface id="120917345196" name="原名"><Resources><Image name="cover" src="preview/preview.png"/></Resources><Theme preview="@cover"/></Watchface>""".toByteArray()
        val refs = mutableListOf<String>()
        WatchfaceXml.parse(xml, "resources/manifest.xml", refs)
        assertEquals(listOf(WatchfacePreview.FIXED), refs)
        val reads = mutableListOf<String>()
        val result = WatchfacePreview.load(temp.newFolder(), "999999999999", { manifest() }, { emptyList() }) {
            reads += it; png
        }
        // Wrong-ID XML preferred paths are never opened; fixed fallback remains available.
        assertEquals(listOf(WatchfacePreview.FIXED), reads)
        assertTrue(result.warnings.any { it.contains("ID 不匹配") })
    }
    @Test fun jpegPairUsesSignatureNotInputFilename() {
        val jpeg = temp.newFile("misleading.png").apply { writeBytes(byteArrayOf(-1, -40, -1, 0)) }
        val store = Store()
        assertTrue(WatchfaceExport.export(store, bin(), jpeg, "XML 名称").complete)
        assertEquals(setOf("XML 名称.bin", "XML 名称.jpg"), store.content.keys)
    }
    private fun working(id: String = "120917345196"): File = temp.newFile().apply {
        writeBytes(ByteArray(512).also { bytes ->
            byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(bytes)
            id.toByteArray().copyInto(bytes, 0x28)
        })
    }
    @Test fun returnedCopyRechecksHashIdAndRetainsOriginalXmlName() {
        val source = working()
        val metadata = WatchfaceMetadata("XML 名称", "120917345196", "description.xml")
        val original = WatchfaceParser.inspect(source).copy(metadata = metadata)
        val copy = working("120917345197")
        val result = WorkingCopyResult.accept(temp.root, copy.path, original)
        assertEquals(metadata, result.metadata)
        assertEquals("120917345197", result.id)
        assertNotEquals(original.sha256, result.sha256)
        assertEquals("120917345196", WatchfaceParser.inspect(source).id)
        assertEquals("XML 名称_ID-120917345197", ExportNames.forInfo(result, true))
        assertTrue(result.metadataWarnings.any { it.contains("ID 已修改") })
        val longName = result.copy(metadata = metadata.copy(name = "中".repeat(200)))
        val base = ExportNames.forInfo(longName, true)
        assertTrue(ExportNames.available(base, listOf("bin", "png"), emptySet()).endsWith("_ID-120917345197"))
    }
    @Test fun returnedCopyRejectsSourceTamperingOutOfRootAndNonIdEdits() {
        val source = working()
        val original = WatchfaceParser.inspect(source)
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(temp.root, source.path, original) }
        val copy = working("120917345197")
        val bytes = copy.readBytes().also { it[200] = 1 }; copy.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(temp.root, copy.path, original) }
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(temp.newFolder(), source.path) }
        val link = File(temp.root, "link.bin"); Files.createSymbolicLink(link.toPath(), source.toPath())
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(temp.root, link.path) }
        source.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(temp.root, copy.path, original) }
    }
}
