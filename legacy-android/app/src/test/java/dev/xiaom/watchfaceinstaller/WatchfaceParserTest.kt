package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM-only tests: real temporary files, no Android runtime or mocked file APIs. */
class WatchfaceParserTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun inspect(bytes: ByteArray): WatchfaceInfo =
        WatchfaceParser.inspect(temporary.newFile().apply { writeBytes(bytes) })

    private fun header(id: String = "123456789", name: String = "测试表盘"): ByteArray =
        ByteArray(0xA8).also {
            byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(it)
            id.toByteArray(Charsets.US_ASCII).copyInto(it, 0x28)
            name.toByteArray(Charsets.UTF_8).copyInto(it, 0x68)
        }

    @Test fun recognizesNineDigitIdAndUtf8Name() {
        val info = inspect(header())
        assertEquals("XIAOMI_BIN", info.format)
        assertEquals("5A A5 34 12", info.magic)
        assertEquals("123456789", info.id)
        assertEquals("测试表盘", info.name)
        assertEquals(64, info.sha256.length)
    }

    private fun headerWithRawName(raw: ByteArray): ByteArray = header(name = "").also {
        require(raw.size <= 60)
        raw.copyInto(it, 0x68)
    }

    @Test fun strictUtf8TakesPriorityOverLegacyDecoders() {
        // These UTF-8 bytes can also be decoded as GB18030, but must stay UTF-8.
        assertEquals("测试表盘", inspect(header()).name)
        assertEquals("晴天☀😀", inspect(header(name = "晴天☀😀")).name)
        assertEquals("Classic", inspect(header(name = "  Classic  ")).name)
    }

    @Test fun decodesVerifiedGb18030AndGbkFixturesWithoutReplacement() {
        // Fixed GBK bytes B2 E2 CA D4 B1 ED C5 CC = 测试表盘.
        val gbk = byteArrayOf(0xb2.toByte(), 0xe2.toByte(), 0xca.toByte(), 0xd4.toByte(),
            0xb1.toByte(), 0xed.toByte(), 0xc5.toByte(), 0xcc.toByte())
        assertEquals("测试表盘", inspect(headerWithRawName(gbk)).name)
        // U+20000 requires the four-byte GB18030 form 95 32 82 36, not GBK.
        val gb18030 = byteArrayOf(0x95.toByte(), 0x32, 0x82.toByte(), 0x36)
        assertEquals("𠀀", inspect(headerWithRawName(gb18030)).name)
    }

    @Test fun capturedBinaryFieldIsUnknownRatherThanReplacementName() {
        // Actual captured header 0x68..0x6f: FF FF FF FF 00 00 00 06.
        val captured = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 6)
        assertNull(inspect(headerWithRawName(captured)).name)
    }

    @Test fun invalidEmptyReplacementAndControlNamesAreUnknown() {
        for (raw in listOf(byteArrayOf(), byteArrayOf(0x81.toByte()), byteArrayOf(-1),
            "   ".toByteArray(), "bad\uFFFDname".toByteArray(), "bad\nname".toByteArray(),
            "bad\u007fname".toByteArray())) {
            assertNull(raw.joinToString(), inspect(headerWithRawName(raw)).name)
        }
    }

    @Test fun nameIsBoundedAndStopsAtFirstNul() {
        assertEquals("Valid", inspect(headerWithRawName("Valid".toByteArray() + byteArrayOf(0, -1))).name)
        val fullField = "a".repeat(60)
        val bytes = headerWithRawName(fullField.toByteArray())
        bytes.fill(-1, 0xa4, 0xa8)
        assertEquals(fullField, inspect(bytes).name)
    }

    @Test fun recognizesTwelveDigitId() {
        assertEquals("123456789012", inspect(header("123456789012")).id)
    }

    @Test fun rejectsIntermediateLengthsAndNonDigits() {
        for (id in listOf("", "12345678", "1234567890", "12345678901", "1234567890123", "12345678a", "123456789 ")) {
            assertNull("ID must not accept $id", inspect(header(id)).id)
        }
    }

    @Test fun rejectsTruncatedXiaomiHeadersAndDoesNotExposePartialFields() {
        val full = header()
        for (length in listOf(1, 2, 3, 4, 0x28, 0x31, 0x68, 0xA7)) {
            val info = inspect(full.copyOf(length))
            assertEquals("length=$length", "TRUNCATED_XIAOMI_BIN", info.format)
            assertNull(info.id)
            assertNull(info.name)
        }
    }

    @Test fun emptyAndUnknownFilesAreNotXiaomi() {
        val empty = inspect(byteArrayOf())
        assertEquals("UNKNOWN", empty.format)
        assertEquals("", empty.magic)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.sha256)
        assertEquals("UNKNOWN", inspect("not a watchface".toByteArray()).format)
    }

    @Test fun zipRequiresFourByteSignature() {
        for (signature in listOf(byteArrayOf(0x50, 0x4b, 3, 4), byteArrayOf(0x50, 0x4b, 5, 6), byteArrayOf(0x50, 0x4b, 7, 8))) {
            assertEquals("ZIP_OR_OTHER", inspect(signature).format)
            for (length in 1..3) assertEquals("UNKNOWN", inspect(signature.copyOf(length)).format)
        }
        assertEquals("UNKNOWN", inspect(byteArrayOf(0x50, 0x4b, 0, 0)).format)
    }

    @Test fun inspectionNeverModifiesSource() {
        val bytes = header()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        WatchfaceParser.inspect(file)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingFile() { WatchfaceParser.inspect(File(temporary.root, "missing.bin")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDirectory() { WatchfaceParser.inspect(temporary.root) }
}
