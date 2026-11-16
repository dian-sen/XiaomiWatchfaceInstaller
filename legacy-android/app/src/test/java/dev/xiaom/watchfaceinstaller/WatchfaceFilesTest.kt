package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class WatchfaceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun generated(size: Long): InputStream = object : InputStream() {
        var remaining = size
        override fun read(): Int = if (remaining-- > 0) 42 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, len.toLong()).toInt()
            b.fill(42, off, off + count)
            remaining -= count
            return count
        }
    }

    @Test fun publishesPrivateCopyWithoutTemporaryLeftovers() {
        val directory = File(temporary.root, "watchfaces")
        val bytes = byteArrayOf(1, 2, 3)
        val file = ByteArrayInputStream(bytes).use { WatchfaceFiles.copyInto(directory, it) }
        assertEquals(directory, file.parentFile)
        assertEquals("bin", file.extension)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(file), directory.listFiles()!!.toList())
    }

    @Test fun acceptsExactly32MiB() {
        val file = generated(WatchfaceFiles.MAX_BYTES).use { WatchfaceFiles.copyInto(temporary.root, it) }
        assertEquals(WatchfaceFiles.MAX_BYTES, file.length())
    }

    @Test fun oversizedCopyIsRejectedAndCleanedUp() {
        try {
            generated(WatchfaceFiles.MAX_BYTES + 1).use { WatchfaceFiles.copyInto(temporary.root, it) }
            fail("must reject oversized input")
        } catch (_: IOException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun readFailureLeavesNoPartialCopy() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("test failure") }
        try {
            input.use { WatchfaceFiles.copyInto(temporary.root, it) }
            fail("must propagate read failure")
        } catch (e: IOException) { assertEquals("test failure", e.message) }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun emptyCopyIsRejectedAndCleanedUp() {
        try {
            ByteArrayInputStream(byteArrayOf()).use { WatchfaceFiles.copyInto(temporary.root, it) }
            fail("must reject empty input")
        } catch (_: IOException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
