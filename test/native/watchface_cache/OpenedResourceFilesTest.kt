package org.zxor.oronbox

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenedResourceFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun helper(): OpenedResourceFiles = OpenedResourceFiles(temporary.newFolder())
    private fun importedFiles(cache: File) = File(cache, "file_open").listFiles().orEmpty().filter { it.isFile }

    @Test fun copiesWithUniquePrefixedNameAndPreservesSupportedExtension() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val first = files.copy(ByteArrayInputStream("first".toByteArray()), "folder/face.bin")
        val second = files.copy(ByteArrayInputStream("second".toByteArray()), "folder/face.bin")
        assertNotEquals(first, second)
        assertTrue(File(first).name.matches(Regex("[0-9a-f-]{36}__face\\.bin")))
        assertTrue(File(second).name.endsWith("__face.bin"))
        assertEquals("first", File(first).readText())
        assertEquals("second", File(second).readText())
        assertTrue(files.release(first))
        assertTrue(files.release(second))
        assertTrue(File(cache, "file_open").listFiles().isNullOrEmpty())
    }

    @Test fun unknownAndRepeatedReleaseAreFalseAndNeverDeleteExternalFiles() {
        val cache = temporary.newFolder()
        val external = File(temporary.newFolder(), "valuable.bin").apply { writeText("preserve") }
        val files = OpenedResourceFiles(cache)
        assertFalse(files.release(external.path))
        assertFalse(files.release("${cache.path}/file_open/arbitrary.bin"))
        assertEquals("preserve", external.readText())
        val imported = files.copy(ByteArrayInputStream(byteArrayOf(1)), "x.bin")
        assertTrue(files.release(imported))
        assertFalse(files.release(imported))
        assertEquals("preserve", external.readText())
        assertEquals(0, importedFiles(cache).size)
    }

    @Test fun rollsBackPartialCopiesWhenStreamFailsOrSourceIsMissing() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val broken = object : InputStream() {
            private var emitted = false
            override fun read(): Int {
                if (!emitted) { emitted = true; return 7 }
                throw IOException("source revoked")
            }
        }
        assertThrows(IOException::class.java) { files.copy(broken, "revoked.bin") }
        assertTrue(importedFiles(cache).isEmpty())
        assertFalse(files.release(null))
        assertThrows(IOException::class.java) { files.copy(nullInput(), "missing.bin") }
        assertTrue(importedFiles(cache).isEmpty())
    }

    @Test fun binAndOtherFilesUseSeparateInclusiveByteLimitsAndRemoveOverflow() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache, maxBinBytes = 3, maxOtherBytes = 5)
        val exactBin = files.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "EXACT.BIN")
        val exactOther = files.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), "archive.zip")
        assertEquals(3L, File(exactBin).length())
        assertEquals(5L, File(exactOther).length())
        assertTrue(files.release(exactBin))
        assertTrue(files.release(exactOther))
        for ((name, bytes) in listOf(
            "overflow.bin" to byteArrayOf(1, 2, 3, 4),
            "overflow.rpk" to byteArrayOf(1, 2, 3, 4, 5, 6),
        )) {
            assertThrows(IOException::class.java) { files.copy(ByteArrayInputStream(bytes), name) }
            assertTrue(importedFiles(cache).isEmpty())
        }
        assertEquals(3L, OpenedResourceFiles.maxBytesForName("UPPER.BIN", 3, 5))
        assertEquals(5L, OpenedResourceFiles.maxBytesForName("pack.zip", 3, 5))
    }

    @Test fun deleteAndCopyAreSerializedAndNeverExposePartialFileToRelease() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val copyStarted = CountDownLatch(1)
        val continueCopy = CountDownLatch(1)
        val copyResult = AtomicReference<String?>()
        val copyFailure = AtomicReference<Throwable?>()
        val source = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position == 0) {
                    position++
                    copyStarted.countDown()
                    if (!continueCopy.await(2, TimeUnit.SECONDS)) throw IOException("test timed out")
                    return 0x42
                }
                return -1
            }
        }
        val copier = Thread {
            try { copyResult.set(files.copy(source, "race.bin")) }
            catch (error: Throwable) { copyFailure.set(error) }
        }
        copier.start()
        assertTrue(copyStarted.await(1, TimeUnit.SECONDS))
        val path = importedFiles(cache).single().absolutePath
        val releaseFinished = CountDownLatch(1)
        val releaseValue = AtomicReference<Boolean>()
        val releaser = Thread {
            releaseValue.set(files.release(path))
            releaseFinished.countDown()
        }
        releaser.start()
        assertFalse(releaseFinished.await(100, TimeUnit.MILLISECONDS))
        assertEquals(0L, File(path).length())
        continueCopy.countDown()
        assertTrue(releaseFinished.await(1, TimeUnit.SECONDS))
        copier.join(2000)
        assertFalse(copier.isAlive)
        copyFailure.get()?.let { throw AssertionError(it) }
        assertEquals(path, copyResult.get())
        assertEquals(true, releaseValue.get())
        assertFalse(File(path).exists())
        assertTrue(importedFiles(cache).isEmpty())
    }

    @Test fun releaseWaitsForCopyLockAndThenDeletesOnlyCompleteRegisteredFile() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val copier = Thread {
            result.set(files.copy(object : InputStream() {
                private var index = 0
                override fun read(): Int {
                    val current = index++
                    if (current == 0) return 1
                    if (current == 1) {
                        started.countDown()
                        if (!resume.await(2, TimeUnit.SECONDS)) throw IOException("timeout")
                        return 2
                    }
                    return -1
                }
            }, "wait.bin"))
        }
        copier.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val returned = CountDownLatch(1)
        val released = AtomicReference<Boolean>()
        val ownedPath = importedFiles(cache).single().absolutePath
        result.set(ownedPath)
        val releaser = Thread { released.set(files.release(ownedPath)); returned.countDown() }
        releaser.start()
        assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
        resume.countDown()
        copier.join(1000)
        releaser.join(1000)
        assertFalse(copier.isAlive)
        assertFalse(releaser.isAlive)
        assertEquals(true, released.get())
        assertFalse(File(result.get()!!).exists())
    }

    @Test fun refusesSymlinkAndTraversalReleaseWithoutTouchingTargets() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val outside = File(temporary.newFolder(), "outside.bin").apply { writeText("secret") }
        val registered = files.copy(ByteArrayInputStream(byteArrayOf(1)), "safe.bin")
        val owned = File(registered)
        assertTrue(owned.delete())
        Files.createSymbolicLink(owned.toPath(), outside.toPath())
        assertFalse(files.release(registered))
        assertEquals("secret", outside.readText())
        assertTrue(Files.deleteIfExists(owned.toPath()))
        assertTrue(files.release(registered))
        assertFalse(files.release("${cache.path}/file_open/../outside.bin"))
        assertEquals("secret", outside.readText())
    }

    @Test fun releaseRejectsDanglingSymlinkAndRestoringOriginalAllowsRetry() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val owned = File(files.copy(ByteArrayInputStream(byteArrayOf(5)), "owned.bin"))
        val original = File(cache, "original.bin")
        assertTrue(owned.renameTo(original))
        val missing = File(temporary.newFolder(), "missing.bin")
        Files.createSymbolicLink(owned.toPath(), missing.toPath())
        assertFalse(files.release(owned.path))
        files.close()
        assertTrue(Files.isSymbolicLink(owned.toPath()))
        assertFalse(missing.exists())
        Files.delete(owned.toPath())
        assertTrue(original.renameTo(owned))
        assertEquals(1, files.cleanupOwned())
        assertFalse(owned.exists())
    }

    @Test fun closeRemovesOnlyRegisteredCopiesAndPreservesPreexistingCacheFiles() {
        val cache = temporary.newFolder()
        val dir = File(cache, "file_open").apply { mkdir() }
        val preexisting = File(dir, "unowned.bin").apply { writeText("user data") }
        val files = OpenedResourceFiles(cache)
        val owned = files.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "owned.bin")
        files.close()
        assertFalse(File(owned).exists())
        assertEquals("user data", preexisting.readText())
        assertFalse(files.release(owned))
        assertThrows(IOException::class.java) { files.copy(ByteArrayInputStream(byteArrayOf(1)), "late.bin") }
        assertTrue(files.cleanupOwned() == 0)
    }

    @Test fun closeWaitsForCopyAndRemovesItsFinalOwnedFile() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val copied = AtomicReference<String?>()
        val copier = Thread {
            copied.set(files.copy(object : InputStream() {
                private var index = 0
                override fun read(): Int {
                    if (index++ == 0) return 0x33
                    if (index == 3) {
                        started.countDown()
                        if (!resume.await(2, TimeUnit.SECONDS)) throw IOException("timeout")
                        return 0x44
                    }
                    return -1
                }
            }, "close.bin"))
        }
        copier.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val closer = Thread { files.close(); closed.countDown() }
        closer.start()
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        resume.countDown()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        copier.join(1000)
        closer.join(1000)
        assertFalse(copier.isAlive)
        assertFalse(closer.isAlive)
        assertNotNull(copied.get())
        assertFalse(File(copied.get()!!).exists())
        assertTrue(importedFiles(cache).isEmpty())
    }

    @Test fun releaseAndClosePreserveReplacementRegularFileUntilOriginalIsRestored() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val owned = File(files.copy(ByteArrayInputStream(byteArrayOf(7)), "owned.bin"))
        val original = File(cache, "original.bin")
        assertTrue(owned.renameTo(original))
        owned.writeText("replacement")
        assertFalse(files.release(owned.path))
        files.close()
        assertEquals("replacement", owned.readText())
        assertArrayEquals(byteArrayOf(7), original.readBytes())
        assertTrue(owned.delete())
        assertTrue(original.renameTo(owned))
        assertEquals(1, files.cleanupOwned())
        assertFalse(owned.exists())
    }

    @Test fun copyAndCleanupRejectSubstitutedDirectoryAndAllowRestoringOriginal() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val owned = File(files.copy(ByteArrayInputStream(byteArrayOf(3)), "owned.bin"))
        val directory = owned.parentFile!!
        val original = File(cache, "original-directory")
        assertTrue(directory.renameTo(original))
        assertTrue(directory.mkdir())
        val replacement = File(directory, owned.name).apply { writeText("preserve") }
        assertFalse(files.release(owned.path))
        assertThrows(IOException::class.java) { files.copy(ByteArrayInputStream(byteArrayOf(4)), "next.bin") }
        files.close()
        assertEquals("preserve", replacement.readText())
        assertTrue(File(original, owned.name).isFile)
        assertTrue(replacement.delete())
        assertTrue(directory.delete())
        assertTrue(original.renameTo(directory))
        assertEquals(1, files.cleanupOwned())
        assertFalse(directory.exists())
    }

    @Test fun failedSourceCloseRollsBackCopyAndClosesSourcesRejectedAfterClose() {
        val cache = temporary.newFolder()
        val files = OpenedResourceFiles(cache)
        val brokenClose = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw IOException("revoked on close") }
        }
        assertThrows(IOException::class.java) { files.copy(brokenClose, "broken.bin") }
        assertTrue(importedFiles(cache).isEmpty())
        files.close()
        var sourceClosed = false
        val late = object : ByteArrayInputStream(byteArrayOf(2)) {
            override fun close() { sourceClosed = true }
        }
        assertThrows(IOException::class.java) { files.copy(late, "late.bin") }
        assertTrue(sourceClosed)
        assertTrue(importedFiles(cache).isEmpty())
    }

    @Test fun filenameIsSafeUtf8BoundedAndPreservesSupportedSuffix() {
        val unsafe = OpenedResourceFiles.safeBasename("../设备<Face>:\u0000漢字.BIN")
        assertTrue(unsafe.endsWith(".BIN"))
        assertFalse(unsafe.contains('/'))
        assertFalse(unsafe.contains('\\'))
        assertFalse(unsafe.any { it.code < 0x20 || it in "<>:\"|?*" })
        val long = OpenedResourceFiles.safeBasename("界".repeat(200) + ".rpk")
        assertTrue(long.endsWith(".rpk"))
        assertTrue(long.toByteArray(StandardCharsets.UTF_8).size <= 180)
        val copied = helper().use { files ->
            files.copy(ByteArrayInputStream(byteArrayOf(1)), "界".repeat(200) + ".face")
        }
        assertTrue(File(copied).name.substringAfter("__").toByteArray(StandardCharsets.UTF_8).size <= 180 - 38)
        assertTrue(File(copied).name.endsWith(".face"))
        assertFalse(File(copied).exists())
    }

    @Test fun basenamesWithDotSegmentsAndUnsupportedExtensionsCannotEscapeOrSpoofBinLimit() {
        for (input in listOf(".", "..", "...", "\\", "a/b\\c.rpk", "x\u202Ebin.rpk", "suffix.")) {
            val name = OpenedResourceFiles.safeBasename(input)
            assertFalse(name == "." || name == ".." || name.contains('/') || name.contains('\\'))
            assertTrue(name.toByteArray(StandardCharsets.UTF_8).size <= 180)
        }
        assertEquals(128L * 1024 * 1024, OpenedResourceFiles.maxBytesForName("face.bin.exe"))
        assertEquals(32L * 1024 * 1024, OpenedResourceFiles.maxBytesForName("face.BIN"))
    }

    private fun nullInput(): InputStream = object : InputStream() {
        override fun read(): Int = -1
        override fun close() { throw IOException("source closed after becoming invalid") }
    }
}
