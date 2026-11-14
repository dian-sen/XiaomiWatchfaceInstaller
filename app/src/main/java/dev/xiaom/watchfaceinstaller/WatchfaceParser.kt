package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Conservative parser for the Xiaomi binary watchface family. */
data class WatchfaceInfo(
    val file: File,
    val magic: String,
    val id: String?,
    val name: String?,
    val format: String,
    val sha256: String
)

object WatchfaceParser {
    private val magic = byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12)

    fun inspect(file: File): WatchfaceInfo {
        require(file.isFile) { "Not a file" }
        val bytes = RandomAccessFile(file, "r").use { raf ->
            val n = minOf(0xA8L, raf.length()).toInt()
            ByteArray(n).also { raf.readFully(it) }
        }
        val isXiaomi = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(magic)
        val completeHeader = isXiaomi && bytes.size >= 0xA8
        val partialMagic = bytes.isNotEmpty() && bytes.size < 4 &&
            bytes.contentEquals(magic.copyOf(bytes.size))
        val id = if (completeHeader) readAscii(bytes, 0x28, 64) else null
        val name = if (completeHeader) readName(bytes, 0x68, 60) else null
        val format = when {
            completeHeader -> "XIAOMI_BIN"
            isXiaomi || partialMagic -> "TRUNCATED_XIAOMI_BIN"
            isZip(bytes) -> "ZIP_OR_OTHER"
            else -> "UNKNOWN"
        }
        return WatchfaceInfo(file, bytes.take(4).joinToString(" ") { "%02X".format(it) }, id,
            name, format, sha256(file))
    }

    private fun readAscii(b: ByteArray, offset: Int, length: Int): String? {
        val raw = b.copyOfRange(offset, offset + length).takeWhile { it.toInt() != 0 }.toByteArray()
        val s = raw.toString(Charsets.US_ASCII)
        return s.takeIf { it.matches(Regex("(?:[0-9]{9}|[0-9]{12})")) }
    }
    private fun readName(b: ByteArray, offset: Int, length: Int): String? {
        val raw = b.copyOfRange(offset, offset + length).takeWhile { it.toInt() != 0 }.toByteArray()
        // UTF-8 always wins when valid. Legacy fallback is a decoding heuristic, not
        // encoding detection or proof that this header variant contains a name here.
        // The captured real header has FF FF FF FF at 0x68: all decoders reject it.
        for (encoding in listOf("UTF-8", "GB18030", "GBK")) {
            val charset = Charset.forName(encoding)
            val decoded = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: CharacterCodingException) { continue }
            // Never turn replacement characters or binary control fields into names.
            if (decoded.any { it == '\uFFFD' || Character.isISOControl(it) }) return null
            if (!decoded.toByteArray(charset).contentEquals(raw)) continue
            return decoded.trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
            (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
            (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf=ByteArray(64*1024); while (true) { val n=input.read(buf); if(n<0) break; md.update(buf,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
