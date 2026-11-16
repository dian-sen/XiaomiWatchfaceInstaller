package android.system

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException

// JVM-only adapter to the host filesystem. The Android-only compile excludes this source.
class ErrnoException(functionName: String, @JvmField val errno: Int, cause: Throwable) : Exception(functionName, cause)

class StructStat(
    @JvmField val st_dev: Long,
    @JvmField val st_ino: Long,
    @JvmField val st_mode: Int,
)

object Os {
    @JvmStatic fun lstat(path: String): StructStat = try {
        val attributes = Files.readAttributes(File(path).toPath(), "unix:dev,ino,mode", LinkOption.NOFOLLOW_LINKS)
        StructStat(
            (attributes.getValue("dev") as Number).toLong(),
            (attributes.getValue("ino") as Number).toLong(),
            (attributes.getValue("mode") as Number).toInt(),
        )
    } catch (error: IOException) {
        val errno = if (error is NoSuchFileException) OsConstants.ENOENT else OsConstants.EIO
        throw ErrnoException("lstat", errno, error)
    }
}

object OsConstants {
    const val ENOENT = 2
    const val EIO = 5
    @JvmStatic fun S_ISDIR(mode: Int): Boolean = mode and 0xF000 == 0x4000
    @JvmStatic fun S_ISREG(mode: Int): Boolean = mode and 0xF000 == 0x8000
}
