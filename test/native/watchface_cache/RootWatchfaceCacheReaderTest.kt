package org.zxor.oronbox

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootWatchfaceCacheReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
    private fun selected(reader: RootWatchfaceCacheReader) = reader.scan().files.single().token
    private fun description(f: WatchfaceCacheFixture, name: String = "XML 名称", id: String = f.id) =
        f.file(f.bin.substringBeforeLast('/') + "/description.xml", "<watch><name>$name</name><pkgName>$id</pkgName></watch>".toByteArray())
    @Suppress("UNCHECKED_CAST")
    private fun previews(bundle: Map<String, Any?>) = bundle["previews"] as List<Map<String, String>>
    @Suppress("UNCHECKED_CAST")
    private fun warnings(bundle: Map<String, Any?>) = bundle["warnings"] as List<String>

    @Test fun copyBundleUsesMatchingXmlNameAndManifestRefsThenCleansOnlyItsOwnFiles() {
        val f = fixture()
        val source = f.file(f.bin, f.binBytes())
        description(f)
        val parent = f.bin.substringBeforeLast('/')
        val manifest = """<Watchface id="${f.id}" name="Manifest"><Theme preview="@best"/><Resources>
            <Image name="best" src="_preview/b.jpg"/><Image name="other" src="_preview/a.png"/>
            <Image name="unsafe" src="../../outside.png"/></Resources></Watchface>""".toByteArray()
        f.file("$parent/resources/manifest.xml", manifest)
        f.file("$parent/resources/_preview/a.png", f.png())
        f.file("$parent/resources/_preview/b.jpg", f.jpeg())
        f.file("$parent/preview/preview.png", f.png())
        val original = source.readBytes()
        val xmlOriginal = f.mapped("$parent/description.xml").readBytes()
        val reader = f.reader()
        val scan = reader.scan()
        val scanMap = scan.toMap()
        assertEquals(setOf("files", "truncated", "diagnostics"), scanMap.keys)
        assertFalse(scanMap.toString().contains(f.root))
        val bundle = reader.copyBundle(scan.files.single().token)
        assertEquals(setOf("path", "name", "embeddedId", "previews", "warnings", "nameSource"), bundle.keys)
        assertEquals("XML 名称", bundle["name"])
        assertEquals("xml", bundle["nameSource"])
        assertEquals(f.id, bundle["embeddedId"])
        assertTrue(warnings(bundle).isEmpty())
        assertEquals(listOf("b.jpg", "a.png", "preview.png"), previews(bundle).map { it["name"] })
        val copy = File(bundle["path"] as String)
        assertArrayEquals(original, copy.readBytes())
        assertTrue(previews(bundle).all { File(it.getValue("path")).isFile })
        reader.cleanupBundle(copy.path)
        assertFalse(copy.parentFile!!.exists())
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(xmlOriginal, f.mapped("$parent/description.xml").readBytes())
    }

    @Test fun mismatchingBinDirectoryOrXmlIdsNeverSupplyNamesOrManifestPriority() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        description(f, id = "123456789")
        f.file(f.bin.substringBeforeLast('/') + "/resources/manifest.xml", "<Watchface id='123456789' name='wrong'/>".toByteArray())
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        assertEquals("BIN name", bundle["name"])
        assertEquals("bin", bundle["nameSource"])
        assertTrue(warnings(bundle).containsAll(listOf("DESCRIPTION_ID_MISMATCH", "MANIFEST_ID_MISMATCH")))
        reader.cleanupBundle(bundle["path"] as String)
        f.file(f.bin, f.binBytes(idValue = "123456789"))
        description(f, id = "123456789")
        val mismatch = reader.copyBundle(selected(reader))
        assertEquals("bin", mismatch["nameSource"])
        assertTrue(warnings(mismatch).containsAll(listOf("PATH_ID_MISMATCH", "DESCRIPTION_ID_MISMATCH")))
        reader.cleanupBundle(mismatch["path"] as String)
    }

    @Test fun missingPathIdStillAcceptsXmlThatMatchesCopiedBinAndManifestNameFallback() {
        val f = fixture()
        val bin = f.root + "/unknown/resource.bin"
        f.file(bin, f.binBytes())
        f.file(bin.substringBeforeLast('/') + "/resources/manifest.xml", "<Watchface id='${f.id}' name='Manifest fallback'/>".toByteArray())
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        assertEquals("Manifest fallback", bundle["name"])
        assertEquals("xml", bundle["nameSource"])
        reader.cleanupBundle(bundle["path"] as String)
    }

    @Test fun invalidXmlAndImageBytesOnlyProduceWarningCodesAndFilenameIsFinalFallback() {
        val f = fixture()
        f.file(f.bin, f.binBytes(name = ""))
        description(f, name = "A<x/>B")
        f.file(f.bin.substringBeforeLast('/') + "/preview/preview.png", "fake image".toByteArray())
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        assertEquals("filename", bundle["nameSource"])
        assertEquals("resource", bundle["name"])
        assertTrue(warnings(bundle).containsAll(listOf("DESCRIPTION_INVALID_XML", "NAME_FILENAME_FALLBACK", "PREVIEW_INVALID_IMAGE", "NO_PREVIEW")))
        assertTrue(previews(bundle).isEmpty())
        assertTrue(warnings(bundle).all { it.matches(Regex("[A-Z_]+")) })
        reader.cleanupBundle(bundle["path"] as String)
    }

    @Test fun invalidIdDoesNotBorrowXmlIdentityAndNonResourceBinIsHidden() {
        val f = fixture()
        f.file(f.bin, f.binBytes(idValue = ""))
        description(f)
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        assertNull(bundle["embeddedId"])
        assertEquals("bin", bundle["nameSource"])
        assertTrue(warnings(bundle).contains("BIN_ID_UNRECOGNIZED"))
        reader.cleanupBundle(bundle["path"] as String)
        f.mapped(f.bin).delete()
        f.file(f.bin.replace("resource.bin", "other.bin"), f.binBytes())
        assertTrue(reader.scan().files.isEmpty())
    }

    @Test fun freshScanInvalidatesTokensAndFailedScanCannotLeaveStaleSelection() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val first = selected(reader)
        val second = selected(reader)
        assertNotEquals(first, second)
        assertEquals("INVALID_TOKEN", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(first) }.code)
        assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(f.bin) }
        var launches = 0
        val badShell = WatchfaceCacheRootShell { _ ->
            launches++
            if (launches == 1) ProcessBuilder("bash", "-c", "printf 'FILE\\0%s\\0%d\\0%d\\0DONE\\0\\0\\0\\0' ${WatchfaceCacheRootPolicy.quote(f.bin)} 168 0").start()
            else ProcessBuilder("bash", "-c", "exit 9").start()
        }
        val badReader = f.reader(badShell)
        val stale = selected(badReader)
        assertThrows(WatchfaceCacheException::class.java) { badReader.scan() }
        assertEquals("INVALID_TOKEN", assertThrows(WatchfaceCacheException::class.java) { badReader.copyBundle(stale) }.code)
    }

    @Test fun changedDisappearedLinkedOrInvalidBinNeverCreatesSession() {
        val f = fixture()
        val file = f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val token = selected(reader)
        file.appendBytes(byteArrayOf(1))
        assertEquals("SOURCE_CHANGED", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        file.delete()
        assertEquals("MISSING", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        val outside = File(f.directory, "outside.bin").apply { writeBytes(f.binBytes()) }
        f.link(f.bin, outside)
        assertEquals("LINK", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        file.delete()
        f.file(f.bin, ByteArray(0xA8))
        assertEquals("INVALID_BIN", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(selected(reader)) }.code)
        assertTrue(f.privateCache.listFiles()!!.isEmpty())
    }

    @Test fun cleanupRequiresExactRegisteredBinPathAndNeverRecursesIntoUnexpectedChildren() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        val bin = File(bundle["path"] as String)
        val unrelated = File(bin.parentFile, "unrelated.txt").apply { writeText("preserve") }
        val otherReader = f.reader()
        for (path in listOf(f.bin, f.privateCache.path, bin.parentFile!!.path, unrelated.path,
            bin.parentFile!!.path + "/./watchface.bin", bin.parentFile!!.path + "/../${bin.parentFile!!.name}/watchface.bin")) {
            assertEquals("UNOWNED_BUNDLE", assertThrows(WatchfaceCacheException::class.java) { reader.cleanupBundle(path) }.code)
        }
        assertEquals("UNOWNED_BUNDLE", assertThrows(WatchfaceCacheException::class.java) { otherReader.cleanupBundle(bin.path) }.code)
        assertThrows(WatchfaceCacheException::class.java) { reader.cleanupBundle(bin.path) }
        assertEquals("preserve", unrelated.readText())
        assertTrue(unrelated.delete())
        reader.cleanupBundle(bin.path)
        assertFalse(bin.parentFile!!.exists())
        assertEquals("UNOWNED_BUNDLE", assertThrows(WatchfaceCacheException::class.java) { reader.cleanupBundle(bin.path) }.code)
    }

    @Test fun cleanupRejectsSubstitutedFileOrSessionSymlinksWithoutTouchingTarget() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        val bin = File(bundle["path"] as String)
        val outside = File(f.directory, "outside.bin").apply { writeText("secret") }
        assertTrue(bin.delete())
        Files.createSymbolicLink(bin.toPath(), outside.toPath())
        assertEquals("PRIVATE_CACHE_CHANGED", assertThrows(WatchfaceCacheException::class.java) { reader.cleanupBundle(bin.path) }.code)
        assertEquals("secret", outside.readText())
        Files.delete(bin.toPath())
        reader.cleanupBundle(bin.path)
        val next = reader.copyBundle(selected(reader))
        val nextBin = File(next["path"] as String)
        val session = nextBin.parentFile!!
        val moved = File(session.parentFile, "moved")
        assertTrue(session.renameTo(moved))
        Files.createSymbolicLink(session.toPath(), moved.toPath())
        assertThrows(WatchfaceCacheException::class.java) { reader.cleanupBundle(nextBin.path) }
        assertTrue(File(moved, "watchface.bin").isFile)
        Files.delete(session.toPath())
        assertTrue(moved.renameTo(session))
        reader.cleanupBundle(nextBin.path)
    }

    @Test fun cleanupRejectsDanglingSymlinkAndRestoringOriginalAllowsRetry() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val bin = File(reader.copyBundle(selected(reader))["path"] as String)
        val original = File(f.directory, "original-private.bin")
        assertTrue(bin.renameTo(original))
        val missing = File(f.directory, "missing.bin")
        Files.createSymbolicLink(bin.toPath(), missing.toPath())
        assertEquals("PRIVATE_CACHE_CHANGED", assertThrows(WatchfaceCacheException::class.java) {
            reader.cleanupBundle(bin.path)
        }.code)
        assertTrue(Files.isSymbolicLink(bin.toPath()))
        assertFalse(missing.exists())
        Files.delete(bin.toPath())
        assertTrue(original.renameTo(bin))
        reader.cleanupBundle(bin.path)
        assertFalse(bin.parentFile!!.exists())
    }

    @Test fun cleanupRejectsReplacementRegularFileAndRestoringOriginalAllowsRetry() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val bin = File(reader.copyBundle(selected(reader))["path"] as String)
        val original = File(f.directory, "original-private.bin")
        assertTrue(bin.renameTo(original))
        bin.writeText("preserve replacement")
        assertEquals("PRIVATE_CACHE_CHANGED", assertThrows(WatchfaceCacheException::class.java) {
            reader.cleanupBundle(bin.path)
        }.code)
        assertEquals("preserve replacement", bin.readText())
        assertTrue(bin.delete())
        assertTrue(original.renameTo(bin))
        reader.cleanupBundle(bin.path)
        assertFalse(bin.parentFile!!.exists())
        assertTrue(f.mapped(f.bin).isFile)
    }

    @Test fun cleanupRejectsReplacementSessionOrBaseAndAllowsRestoringOriginal() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val bin = File(reader.copyBundle(selected(reader))["path"] as String)
        val session = bin.parentFile!!
        val originalSession = File(f.directory, "original-session")
        assertTrue(session.renameTo(originalSession))
        assertTrue(session.mkdir())
        assertEquals("PRIVATE_CACHE_CHANGED", assertThrows(WatchfaceCacheException::class.java) {
            reader.cleanupBundle(bin.path)
        }.code)
        assertTrue(session.isDirectory)
        assertTrue(File(originalSession, bin.name).isFile)
        assertTrue(session.delete())
        assertTrue(originalSession.renameTo(session))
        val base = session.parentFile!!
        val originalBase = File(f.directory, "original-base")
        assertTrue(base.renameTo(originalBase))
        assertTrue(base.mkdir())
        assertEquals("PRIVATE_CACHE_CHANGED", assertThrows(WatchfaceCacheException::class.java) {
            reader.cleanupBundle(bin.path)
        }.code)
        assertTrue(base.isDirectory)
        assertTrue(base.delete())
        assertTrue(originalBase.renameTo(base))
        reader.cleanupBundle(bin.path)
        assertFalse(session.exists())
    }

    @Test fun privateBaseSymlinkIsRejectedBeforeWritingAndExact32MiBBinIsAccepted() {
        val f = fixture()
        val source = f.file(f.bin, f.binBytes())
        val outside = File(f.directory, "outside").apply { mkdirs() }
        val base = File(f.privateCache, "watchface_cache_import")
        Files.createSymbolicLink(base.toPath(), outside.toPath())
        val reader = f.reader()
        assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(selected(reader)) }
        assertTrue(outside.listFiles()!!.isEmpty())
        Files.delete(base.toPath())
        RandomAccessFile(source, "rw").use { it.setLength(WatchfaceCacheRootPolicy.MAX_BIN_BYTES) }
        val bundle = reader.copyBundle(selected(reader))
        assertEquals(WatchfaceCacheRootPolicy.MAX_BIN_BYTES, File(bundle["path"] as String).length())
        reader.cleanupBundle(bundle["path"] as String)
    }

    @Test fun previewAndActiveSessionCountsAreBounded() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val previewRoot = f.bin.substringBeforeLast('/') + "/resources/_preview/"
        for (index in 0..40) f.file(previewRoot + "p$index.png", f.png())
        val reader = f.reader()
        val token = selected(reader)
        val bundles = (1..8).map { reader.copyBundle(token) }
        assertTrue(bundles.all { previews(it).size == 8 })
        assertTrue(warnings(bundles.first()).containsAll(listOf("PREVIEW_LIST_LIMIT", "PREVIEW_BUNDLE_LIMIT")))
        assertEquals("PRIVATE_SESSION_LIMIT", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        bundles.forEach { reader.cleanupBundle(it["path"] as String) }
        assertTrue(File(f.privateCache, "watchface_cache_import").listFiles()!!.isEmpty())
    }

    @Test fun oversizedXmlAndPreviewAreSkippedAndAggregatePreviewBytesStayBounded() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val xml = description(f)
        RandomAccessFile(xml, "rw").use { it.setLength(WatchfaceCacheMetadata.MAX_XML_BYTES + 1L) }
        val previewRoot = f.bin.substringBeforeLast('/') + "/resources/_preview/"
        for (index in 0..3) {
            val image = f.file(previewRoot + "p$index.png", f.png())
            RandomAccessFile(image, "rw").use { it.setLength(WatchfaceCacheMetadata.MAX_PREVIEW_BYTES.toLong()) }
        }
        val oversized = f.file(previewRoot + "large.jpg", f.jpeg())
        RandomAccessFile(oversized, "rw").use { it.setLength(WatchfaceCacheMetadata.MAX_PREVIEW_BYTES + 1L) }
        val reader = f.reader()
        val bundle = reader.copyBundle(selected(reader))
        assertEquals(2, previews(bundle).size)
        assertEquals(16L * 1024 * 1024, previews(bundle).sumOf { File(it.getValue("path")).length() })
        assertTrue(warnings(bundle).containsAll(listOf("DESCRIPTION_LIMIT", "PREVIEW_LIMIT", "PREVIEW_BUNDLE_LIMIT")))
        reader.cleanupBundle(bundle["path"] as String)
    }

    @Test fun closeClearsTokensCleansEveryRegisteredBundleAndDoesNotStartRoot() {
        val f = fixture()
        val source = f.file(f.bin, f.binBytes())
        val original = source.readBytes()
        val reader = f.reader()
        val token = selected(reader)
        val bundles = (1..3).map { reader.copyBundle(token) }
        reader.close()
        bundles.forEach { assertFalse(File(it["path"] as String).parentFile!!.exists()) }
        assertArrayEquals(original, source.readBytes())
        assertEquals("READER_CLOSED", assertThrows(WatchfaceCacheException::class.java) { reader.scan() }.code)
        assertEquals("READER_CLOSED", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        reader.close()
        RootWatchfaceCacheReader(f.privateCache, WatchfaceCacheRootShell { throw AssertionError("close must not start su") }).close()
    }

    @Test fun cleanupAllAndCloseContinuePastUnsafeSessionAndSupportSafeRetry() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val token = selected(reader)
        val first = File(reader.copyBundle(token)["path"] as String)
        val second = File(reader.copyBundle(token)["path"] as String)
        val third = File(reader.copyBundle(token)["path"] as String)
        val foreign = File(first.parentFile, "foreign.txt").apply { writeText("preserve") }
        val outside = File(f.directory, "outside.bin").apply { writeText("secret") }
        assertTrue(second.delete())
        Files.createSymbolicLink(second.toPath(), outside.toPath())
        val failure = assertThrows(WatchfaceCacheException::class.java) { reader.close() }
        assertEquals("PRIVATE_CACHE_CHANGED", failure.code)
        assertEquals(1, failure.suppressed.size)
        assertFalse(third.parentFile!!.exists())
        assertEquals("preserve", foreign.readText())
        assertEquals("secret", outside.readText())
        assertTrue(foreign.delete())
        Files.delete(second.toPath())
        reader.close()
        assertFalse(first.parentFile!!.exists())
        assertFalse(second.parentFile!!.exists())
    }

    @Test fun cleanupAllAllowsNewWorkWithoutClosingReader() {
        val f = fixture()
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val token = selected(reader)
        val first = File(reader.copyBundle(token)["path"] as String)
        reader.cleanupAll()
        assertFalse(first.parentFile!!.exists())
        val second = File(reader.copyBundle(token)["path"] as String)
        assertTrue(second.isFile)
        reader.close()
    }

    @Test fun failedRootOutputCannotPublishPartialPrivateBin() {
        val f = fixture()
        var calls = 0
        val shell = WatchfaceCacheRootShell { _ ->
            calls++
            val script = if (calls == 1) "printf 'FILE\\0%s\\0%d\\0%d\\0DONE\\0\\0\\0\\0' ${WatchfaceCacheRootPolicy.quote(f.bin)} 168 0"
                else "printf partial; exit 42"
            ProcessBuilder("bash", "-c", script).start()
        }
        val reader = f.reader(shell)
        val token = selected(reader)
        assertEquals("DENIED", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        assertTrue(f.privateCache.listFiles()!!.isEmpty())
    }
}
