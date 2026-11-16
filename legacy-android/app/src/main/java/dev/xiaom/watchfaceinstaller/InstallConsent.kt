package dev.xiaom.watchfaceinstaller

import java.io.File
import java.io.RandomAccessFile

/** Non-secret, immutable facts consented to by the user; never accepts the official cache path. */
@ConsistentCopyVisibility
data class InstallConsent private constructor(
    val path: String, val name: String, val id: String, val size: Long, val sha256: String
) {
    fun verify(privateRoot: File, file: File) {
        require(this == capture(privateRoot, file)) { "确认后私有文件已变化；拒绝发送，请重新检查并确认" }
    }
    companion object {
        fun capture(privateRoot: File, file: File): InstallConsent {
            val source = WorkingCopyResult.privateFile(privateRoot, file.path)
            val size = source.length()
            require(size in 0xa8..WatchfaceIdEditor.MAX_BYTES) { "BIN 大小不合法" }
            val id = RandomAccessFile(source, "r").use {
                WatchfaceIdEditor.validatedId(ByteArray(0xa8).also(it::readFully))
            }
            val hash = WatchfaceIdEditor.sha256(source)
            require(size == source.length()) { "校验期间文件已变化" }
            return InstallConsent(source.path, source.name, id, size, hash)
        }
    }
}
