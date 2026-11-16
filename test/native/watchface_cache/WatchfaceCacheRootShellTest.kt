package org.zxor.oronbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class WatchfaceCacheRootShellTest {
    private fun shell(timeout: Long = 3000) = WatchfaceCacheRootShell(timeout) { ProcessBuilder("bash", "-c", it).start() }
    private fun code(block: () -> Unit) = assertThrows(WatchfaceCacheException::class.java, block).code

    @Test fun verifiesExactLimitEmptyOutputStderrAndExitStatus() {
        assertEquals("1234", shell().read("printf 1234", 4).toString(Charsets.UTF_8))
        assertTrue(shell().read("exit 0", 0).isEmpty())
        assertEquals("OUTPUT_LIMIT", code { shell().read("printf 12345", 4) })
        assertEquals("DENIED", code { shell().read("printf partial; exit 42", 100) })
        assertEquals("ROOT_STDERR", code { shell().read("printf data; printf denied >&2", 100) })
        assertEquals("ROOT_DENIED", code { shell().read("exit 77", 100) })
    }

    @Test(timeout = 5000) fun boundsStderrAndStdoutWithoutPipeDeadlocks() {
        val started = System.nanoTime()
        assertEquals("STDERR_LIMIT", code { shell().read("head -c 100000 /dev/zero >&2; exec sleep 3", 1) })
        assertEquals("OUTPUT_LIMIT", code { shell().read("head -c 100000 /dev/zero; exec sleep 3", 1) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
    }

    @Test(timeout = 5000) fun timeoutAndInterruptionReturnPromptlyAndPreserveFlag() {
        val started = System.nanoTime()
        assertEquals("ROOT_TIMEOUT", code { shell(100).read("exec sleep 3", 1) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
        val failure = AtomicReference<Throwable?>()
        val owner = Thread {
            try {
                shell().read("exec sleep 3", 10)
                failure.set(AssertionError("must be cancelled"))
            } catch (error: WatchfaceCacheException) {
                if (error.code != "CANCELLED" || !Thread.currentThread().isInterrupted) failure.set(error)
            } catch (error: Throwable) { failure.set(error) }
        }
        owner.start()
        Thread.sleep(50)
        owner.interrupt()
        owner.join(1500)
        assertFalse(owner.isAlive)
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test(timeout = 5000) fun blockedProcessStartIsBoundedAndLateProcessIsDisposed() {
        val release = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val process = fakeProcess(destroyed = destroyed)
        val shell = WatchfaceCacheRootShell(100) {
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
            process
        }
        assertEquals("ROOT_TIMEOUT", code { shell.read("ignored", 10) })
        release.countDown()
        val deadline = System.nanoTime() + 1_000_000_000
        while (!destroyed.get() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(destroyed.get())
    }

    @Test(timeout = 5000) fun destructionThatHoldsAStreamLockCannotBlockCaller() {
        val release = CountDownLatch(1)
        val hanging = object : InputStream() {
            override fun read(): Int {
                while (true) {
                    try { release.await(); return -1 } catch (_: InterruptedException) { }
                }
            }
            override fun close() { release.await() }
        }
        val process = fakeProcess(stdout = hanging, beforeDestroy = { release.await() })
        val shell = WatchfaceCacheRootShell(100) { process }
        val started = System.nanoTime()
        assertEquals("ROOT_TIMEOUT", code { shell.read("ignored", 1) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
        release.countDown()
    }

    @Test(timeout = 3000) fun unavailableApi26DestroyForciblyFallsBackToDestroy() {
        val destroyed = AtomicBoolean(false)
        val process = fakeProcess(destroyed = destroyed, beforeForceDestroy = {
            throw NoSuchMethodError("Process.destroyForcibly is unavailable before Android API 26")
        })
        assertTrue(WatchfaceCacheRootShell { process }.read("ignored", 1).isEmpty())
        val deadline = System.nanoTime() + 1_000_000_000
        while (!destroyed.get() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(destroyed.get())
    }

    @Test fun failedStartInvalidLimitsAndPreInterruptedCallerNeverPublishBytes() {
        assertEquals("ROOT_UNAVAILABLE", code { WatchfaceCacheRootShell { throw IOException("unavailable") }.read("ignored", 1) })
        val shell = WatchfaceCacheRootShell { throw AssertionError("must not start") }
        assertThrows(IllegalArgumentException::class.java) { shell.read("ignored", -1) }
        assertThrows(IllegalArgumentException::class.java) { shell.read("ignored", WatchfaceCacheRootPolicy.MAX_BIN_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRootShell(0) }
        Thread.currentThread().interrupt()
        try { assertEquals("CANCELLED", code { shell.read("ignored", 1) }) }
        finally { Thread.interrupted() }
    }

    private fun fakeProcess(
        stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
        destroyed: AtomicBoolean = AtomicBoolean(),
        beforeDestroy: () -> Unit = {},
        beforeForceDestroy: () -> Unit = {},
    ) = object : Process() {
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { beforeDestroy(); destroyed.set(true) }
        override fun destroyForcibly(): Process { beforeForceDestroy(); destroy(); return this }
    }
}
