package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

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
        val id = if (isXiaomi && bytes.size > 0x28) readAscii(bytes, 0x28, minOf(64, bytes.size - 0x28)) else null
        val name = if (isXiaomi && bytes.size > 0x68) readUtf8(bytes, 0x68, minOf(60, bytes.size - 0x68)) else null
        return WatchfaceInfo(file, bytes.take(4).joinToString(" ") { "%02X".format(it) }, id,
            name, if (isXiaomi) "XIAOMI_BIN" else if (isZip(file)) "ZIP_OR_OTHER" else "UNKNOWN", sha256(file))
    }

    private fun readAscii(b: ByteArray, offset: Int, length: Int): String? {
        val raw = b.copyOfRange(offset, offset + length).takeWhile { it.toInt() != 0 }.toByteArray()
        val s = raw.toString(Charsets.US_ASCII)
        return s.takeIf { it.matches(Regex("[0-9]{9,12}")) }
    }
    private fun readUtf8(b: ByteArray, offset: Int, length: Int): String? =
        b.copyOfRange(offset, offset + length).takeWhile { it.toInt() != 0 }.toByteArray()
            .toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    private fun isZip(file: File): Boolean = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf=ByteArray(64*1024); while (true) { val n=input.read(buf); if(n<0) break; md.update(buf,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
