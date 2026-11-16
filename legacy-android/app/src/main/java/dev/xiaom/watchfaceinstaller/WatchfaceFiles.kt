package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Owns only private copies; never edits the source. Call on Dispatchers.IO. */
object WatchfaceFiles {
    const val MAX_BYTES = 32L * 1024 * 1024

    fun copyInto(directory: File, input: InputStream): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建表盘目录" }
        val temporary = File.createTempFile(".import-", ".tmp", directory)
        val destination = File(directory, "${UUID.randomUUID()}.bin")
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw IOException("文件超过 32 MiB")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("不能导入空文件")
                output.fd.sync()
            }
            // Same-directory atomic publish: partial copies are never selectable.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return destination
        } finally { temporary.delete() }
    }
}
