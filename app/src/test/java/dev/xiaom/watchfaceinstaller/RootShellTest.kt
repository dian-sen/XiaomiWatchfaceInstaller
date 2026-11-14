package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** ProcessBuilder(sh) is injected everywhere: tests never execute su. */
class RootShellTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun shell(timeout: Long = 5000) = RootShell(timeout) { ProcessBuilder("sh", "-c", it).start() }
    private val path get() = RootPolicy.roots.first() + "/a.bin"
    private fun source(command: String, timeout: Long = 5000) = RootCacheSource(RootShell(timeout) {
        ProcessBuilder("sh", "-c", command).start()
    })

    @Test fun readsTextAndEmptyOutput() {
        assertEquals("hello\n", shell().text("printf 'hello\\n'", 6))
        assertEquals("", shell().text("exit 0", 0))
    }

    @Test fun acceptsExactStdoutLimitAndRejectsOverflow() {
        assertEquals("1234", shell().text("printf 1234", 4))
        val e = assertThrows(IOException::class.java) { shell().text("printf 12345", 4) }
        assertTrue(e.message!!.contains("超限"))
    }

    @Test fun rejectsNonzeroExitEvenAfterValidOutput() {
        val e = assertThrows(IOException::class.java) { shell().text("printf data; exit 7", 64) }
        assertTrue(e.message!!.contains("exit=7"))
    }

    @Test fun rejectsStderrEvenWithZeroExit() {
        val e = assertThrows(IOException::class.java) { shell().text("printf denied >&2", 64) }
        assertTrue(e.message!!.contains("denied"))
    }

    @Test(timeout = 5000) fun boundsStderrAndDoesNotDeadlock() {
        assertThrows(IOException::class.java) {
            shell().text("i=0; while [ \"\$i\" -lt 10000 ]; do printf x >&2; i=\$((i+1)); done", 32)
        }
    }

    @Test(timeout = 5000) fun timeoutBoundsSilentProcess() {
        val started = System.nanoTime()
        val e = assertThrows(IOException::class.java) { shell(150).text("exec sleep 3", 64) }
        assertTrue(e.message!!.contains("超时"))
        assertTrue("timeout must return promptly", (System.nanoTime() - started) / 1_000_000 < 2000)
    }

    @Test(timeout = 5000) fun timeoutBoundsDescendantHoldingStdoutOpen() {
        val started = System.nanoTime()
        // Parent exits, but a descendant retains stdout/stderr: waitFor alone is insufficient.
        assertThrows(IOException::class.java) { shell(150).text("sleep 3 & exit 0", 64) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
    }

    @Test(timeout = 5000) fun interruptionCancelsAndPreservesInterruptFlag() {
        val result = AtomicReference<Throwable?>()
        val owner = Thread {
            try { shell().text("exec sleep 3", 64); result.set(AssertionError("must cancel")) }
            catch (e: IOException) { if (!Thread.currentThread().isInterrupted) result.set(AssertionError("interrupt lost", e)) }
            catch (e: Throwable) { result.set(e) }
        }
        owner.start()
        Thread.sleep(100)
        owner.interrupt()
        owner.join(2000)
        assertFalse(owner.isAlive)
        result.get()?.let { throw AssertionError(it) }
    }

    @Test fun wrapsProcessStartFailure() {
        val e = assertThrows(IOException::class.java) {
            RootShell(start = { throw IOException("no process") }).text("ignored", 64)
        }
        assertTrue(e.message!!.contains("无法启动 su"))
    }

    @Test fun rejectsInvalidLimitsBeforeStartingProcess() {
        val shell = RootShell(start = { throw AssertionError("must not start") })
        assertThrows(IllegalArgumentException::class.java) { shell.text("ignored", -1) }
        assertThrows(IllegalArgumentException::class.java) { shell.read("ignored", WatchfaceFiles.MAX_BYTES + 1) { } }
        assertThrows(IllegalArgumentException::class.java) { RootShell(0) }
    }

    @Test fun authorizesOnlyUidZero() {
        assertTrue(source("printf '0\\n'").authorize().contains("UID 0"))
        assertThrows(IOException::class.java) { source("printf '2000\\n'").authorize() }
    }

    @Test fun verifiesExitBeforeInvokingConsumer() {
        var consumed = false
        assertThrows(IOException::class.java) {
            shell().read("printf data; exit 9") { consumed = true; it.readBytes() }
        }
        assertFalse(consumed)
    }

    @Test fun successfulCopyPublishesOnlyVerifiedBytes() {
        val file = source("printf data").copyInto(temporary.root, path)
        assertEquals("data", file.readText())
        assertEquals(listOf(file), temporary.root.listFiles()!!.toList())
    }

    @Test fun failedCopiesLeaveNoPrivateOrTemporaryFiles() {
        for (command in listOf("printf partial; exit 7", "printf partial; printf denied >&2", "exit 0")) {
            assertThrows(IOException::class.java) { source(command).copyInto(temporary.root, path) }
            assertTrue("leftovers after $command", temporary.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun oversizedRootOutputLeavesNoCopy() {
        assertThrows(IOException::class.java) {
            source("head -c 33554433 /dev/zero").copyInto(temporary.root, path)
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test(timeout = 5000) fun timedOutCopyCannotPublishLater() {
        assertThrows(IOException::class.java) { source("printf partial; exec sleep 1", 100).copyInto(temporary.root, path) }
        Thread.sleep(1100)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectedPathNeverStartsProcessOrCreatesCopy() {
        val source = RootCacheSource(RootShell(start = { throw AssertionError("must not start") }))
        assertThrows(IllegalArgumentException::class.java) { source.copyInto(temporary.root, "/etc/a.bin") }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun localPublishFailureDoesNotLeaveTemporaryFile() {
        val directory = temporary.newFile("not-directory")
        assertThrows(IllegalStateException::class.java) { source("printf data").copyInto(directory, path) }
        assertEquals(0L, directory.length())
        assertEquals(listOf(directory), temporary.root.listFiles()!!.toList())
    }
}
