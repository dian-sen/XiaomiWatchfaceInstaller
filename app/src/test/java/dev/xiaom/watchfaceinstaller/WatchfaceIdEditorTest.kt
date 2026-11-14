package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WatchfaceIdEditorTest {
    private fun fixture(id: String = "123456789"): ByteArray = ByteArray(512) { (it * 31).toByte() }.apply {
        byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(this)
        id.toByteArray(Charsets.US_ASCII).copyInto(this, 0x28)
        this[0x28 + id.length] = 0
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun copiesPreserveEveryNonIdByteAndSourceForBothLengths() {
        val root = Files.createTempDirectory("id-test").toFile()
        try {
            for ((old, next) in listOf("123456789" to "987654321", "123456789012" to "987654321098")) {
                val bytes = fixture(old)
                val source = File(root, "$old.bin").apply { writeBytes(bytes) }
                val result = WatchfaceIdEditor.createCopy(root, source, next)
                assertTrue(result.file.canonicalPath.startsWith(File(root, "watchfaces/id-copies").canonicalPath + File.separator))
                assertArrayEquals(bytes, source.readBytes())
                assertEquals(old, result.oldId); assertEquals(next, result.newId)
                val expected = bytes.copyOf().apply { next.toByteArray().copyInto(this, 0x28) }
                assertArrayEquals(expected, result.file.readBytes())
                assertEquals(WatchfaceIdEditor.sha256(result.file), result.sha256)
                assertNotEquals(WatchfaceIdEditor.sha256(source), result.sha256)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsWrongLengthSameIdUnknownMagicAndCleansFailedCopies() {
        val root = Files.createTempDirectory("id-test").toFile()
        try {
            val source = File(root, "in.bin").apply { writeBytes(fixture()) }
            for (id in listOf("123456789012", "123456789", "12345678x", "１２３４５６７８９", "123")) {
                rejected { WatchfaceIdEditor.createCopy(root, source, id) }
            }
            source.writeBytes(fixture().apply { this[0] = 0 })
            rejected { WatchfaceIdEditor.createCopy(root, source, "987654321") }
            assertEquals(0, File(root, "watchfaces/id-copies").listFiles()?.size ?: 0)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTruncatedNonNumericAndUnterminatedHeaders() {
        rejected { WatchfaceIdEditor.validatedId(ByteArray(16)) }
        rejected { WatchfaceIdEditor.validatedId(fixture("12345678x")) }
        rejected { WatchfaceIdEditor.validatedId(fixture().apply { for (i in 0x28 until 0x68) this[i] = 49 }) }
    }
    @Test fun rejectsOutsideAndSymlinksIncludingOutputParent() {
        val root = Files.createTempDirectory("id-test").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            val source = File(outside, "in.bin").apply { writeBytes(fixture()) }
            rejected { WatchfaceIdEditor.createCopy(root, source, "987654321") }
            val link = File(root, "in.bin")
            Files.createSymbolicLink(link.toPath(), source.toPath())
            rejected { WatchfaceIdEditor.createCopy(root, link, "987654321") }
            val local = File(root, "local.bin").apply { writeBytes(fixture()) }
            Files.createSymbolicLink(File(root, "watchfaces").toPath(), outside.toPath())
            rejected { WatchfaceIdEditor.createCopy(root, local, "987654321") }
            assertFalse(File(outside, "id-copies").exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
