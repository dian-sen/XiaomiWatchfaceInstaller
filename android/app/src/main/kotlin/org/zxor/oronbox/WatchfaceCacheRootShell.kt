package org.zxor.oronbox

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class WatchfaceCacheException(
    val code: String,
    message: String = code,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Adapted from dian-sen/XiaomiWatchfaceInstaller, RootCacheSource.kt (RootShell).
 * https://github.com/dian-sen/XiaomiWatchfaceInstaller
 * Start, pipe reads, process reaping and destruction never block the caller past its deadline.
 * Bytes are published only after bounded stdout/stderr, EOF and exit status are verified.
 */
internal class WatchfaceCacheRootShell(
    private val timeoutMillis: Long = 20_000,
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
) {
    init {
        require(timeoutMillis in 1..300_000)
    }

    /** Fixed, bounded root authorization probe; no caller-controlled shell program. */
    fun authorize(): Boolean {
        if (utf8(read("id -u", 16, 10_000)).trim() != "0") throw WatchfaceCacheException("ROOT_DENIED")
        return true
    }

    fun read(command: String, limit: Long, budgetMillis: Long = timeoutMillis): ByteArray {
        require(limit in 0..WatchfaceCacheRootPolicy.MAX_BIN_BYTES && budgetMillis > 0)
        if (Thread.currentThread().isInterrupted) throw WatchfaceCacheException("CANCELLED")
        val processRef = AtomicReference<Process?>(null)
        val cancelled = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        fun dispose(process: Process) {
            if (!disposed.compareAndSet(false, true)) return
            Thread({
                runCatching { process.destroyForcibly() }.onFailure { runCatching { process.destroy() } }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }, "watchface-root-cleanup").apply { isDaemon = true; start() }
        }
        val pump = FutureTask<ByteArray> {
            val process = try {
                start(command)
            } catch (error: IOException) {
                throw WatchfaceCacheException("ROOT_UNAVAILABLE", cause = error)
            }
            processRef.set(process)
            if (cancelled.get()) {
                dispose(process)
                throw WatchfaceCacheException("CANCELLED")
            }
            val stderr = ByteArrayOutputStream()
            val errorFailure = AtomicReference<IOException?>(null)
            val errorReader = Thread({
                try {
                    process.errorStream.use { input ->
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (stderr.size() + count > 8192) {
                                throw WatchfaceCacheException("STDERR_LIMIT")
                            }
                            stderr.write(buffer, 0, count)
                        }
                    }
                } catch (error: IOException) {
                    errorFailure.set(error)
                    dispose(process)
                }
            }, "watchface-root-stderr").apply { isDaemon = true; start() }
            try {
                process.outputStream.close()
                val stdout = ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (stdout.size().toLong() + count > limit) {
                            throw WatchfaceCacheException("OUTPUT_LIMIT")
                        }
                        stdout.write(buffer, 0, count)
                    }
                }
                val exit = process.waitFor()
                errorReader.join()
                errorFailure.get()?.let { throw it }
                if (exit != 0) throw WatchfaceCacheException(exitCode(exit))
                if (stderr.size() != 0) throw WatchfaceCacheException("ROOT_STDERR")
                stdout.toByteArray()
            } catch (error: IOException) {
                throw (errorFailure.get() ?: error)
            } finally {
                dispose(process)
            }
        }
        Thread(pump, "watchface-root-stdout").apply { isDaemon = true; start() }
        return try {
            pump.get(minOf(timeoutMillis, budgetMillis), TimeUnit.MILLISECONDS).also {
                if (Thread.currentThread().isInterrupted) throw WatchfaceCacheException("CANCELLED")
            }
        } catch (error: TimeoutException) {
            throw WatchfaceCacheException("ROOT_TIMEOUT", cause = error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WatchfaceCacheException("CANCELLED", cause = error)
        } catch (error: ExecutionException) {
            throw (error.cause as? IOException ?: WatchfaceCacheException("ROOT_IO", cause = error.cause))
        } finally {
            cancelled.set(true)
            pump.cancel(true)
            processRef.get()?.let(::dispose)
        }
    }

    companion object {
        private fun exitCode(exit: Int): String = when (exit) {
            41 -> "MISSING"
            42 -> "DENIED"
            43 -> "LINK"
            44 -> "ESCAPE"
            45 -> "NOT_REGULAR"
            46 -> "LIMIT"
            47 -> "CHANGED"
            77 -> "ROOT_DENIED"
            else -> "ROOT_IO"
        }

        fun utf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw WatchfaceCacheException("INVALID_UTF8", cause = error)
        }
    }
}
