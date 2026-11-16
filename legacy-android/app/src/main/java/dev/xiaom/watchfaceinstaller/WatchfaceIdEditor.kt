package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/** A byte-preserving header edit, NOT a signature/checksum bypass or compatibility conversion. */
object WatchfaceIdEditor {
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val ID_OFFSET = 0x28
    private const val ID_END = 0x68
    private val magic = byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12)
    data class Result(val file: File, val oldId: String, val newId: String, val sha256: String)

    fun validatedId(header: ByteArray): String {
        require(header.size >= 0xa8 && header.copyOfRange(0, 4).contentEquals(magic)) {
            "只支持已识别的完整 5AA53412 头；未知格式拒绝修改"
        }
        val end = (ID_OFFSET until ID_END).firstOrNull { header[it] == 0.toByte() }
            ?: error("ID 缺少字段内 NUL 终止符")
        val length = end - ID_OFFSET
        require(length == 9 || length == 12) { "当前格式 ID 长度未经支持：$length" }
        require((ID_OFFSET until end).all { header[it].toInt() in 48..57 }) { "原 ID 不是 ASCII 数字" }
        // No assumption about bytes after the terminator: preserve all of them exactly.
        return String(header, ID_OFFSET, length, Charsets.US_ASCII)
    }

    /** Input and output must both live under the app's canonical private directory. */
    fun createCopy(privateRoot: File, source: File, newId: String): Result {
        val root = privateRoot.canonicalFile
        val original = WorkingCopyResult.privateFile(privateRoot, source.path)
        require(newId.matches(Regex("(?:[0-9]{9}|[0-9]{12})"))) { "新 ID 必须为 9 或 12 位数字" }
        val watchfaces = File(root, "watchfaces")
        require(!Files.isSymbolicLink(watchfaces.toPath())) { "拒绝表盘目录符号链接" }
        val directory = File(watchfaces, "id-copies").apply { mkdirs() }
        require(directory.canonicalFile.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(directory.toPath()))
        val copy = File.createTempFile("watchface-", ".bin", directory)
        try {
            // Snapshot first; all inspection and mutation applies to that snapshot, never to source.
            original.inputStream().use { input -> copy.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= MAX_BYTES) { "源文件增长超过大小上限" }
                    output.write(buffer, 0, n)
                }
            } }
            val oldId = RandomAccessFile(copy, "rw").use { file ->
                require(file.length() >= 0xa8)
                val header = ByteArray(0xa8).also { file.readFully(it) }
                val old = validatedId(header)
                require(old.length == newId.length) { "必须保持原格式的 ${old.length} 位长度，不做 9/12 位转换" }
                require(old != newId) { "新旧 ID 相同" }
                file.seek(ID_OFFSET.toLong()); file.write(newId.toByteArray(Charsets.US_ASCII))
                file.fd.sync()
                file.seek(0); file.readFully(header)
                check(validatedId(header) == newId)
                old
            }
            return Result(copy, oldId, newId, sha256(copy))
        } catch (error: Throwable) { copy.delete(); throw error }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
