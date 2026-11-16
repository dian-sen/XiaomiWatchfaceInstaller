package org.zxor.oronbox

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** API 21 lstat preserves inode checks without following links or requiring java.nio.file (API 26). */
internal data class PrivateCacheFileIdentity private constructor(val device: Long, val inode: Long) {
    companion object {
        fun read(file: File, directory: Boolean = false): PrivateCacheFileIdentity =
            readIfPresent(file, directory) ?: throw IOException("Private cache path is missing")

        fun readIfPresent(file: File, directory: Boolean = false): PrivateCacheFileIdentity? {
            val stat = try {
                Os.lstat(file.path)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return null
                throw IOException("Unable to verify private cache identity", error)
            }
            val expectedType = if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)
            if (!expectedType) throw IOException("Private cache path type changed")
            return PrivateCacheFileIdentity(stat.st_dev, stat.st_ino)
        }
    }
}
