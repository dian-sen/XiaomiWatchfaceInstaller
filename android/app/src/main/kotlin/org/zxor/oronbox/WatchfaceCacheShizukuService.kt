package org.zxor.oronbox

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loaded by Shizuku in its own UID 0/2000 process; never exported as an Android Service. */
class WatchfaceCacheShizukuService : IWatchfaceCacheShizukuService.Stub() {
    private val busy = Semaphore(1)
    private val closed = AtomicBoolean(false)
    private val active = linkedSetOf<ParcelFileDescriptor>()
    private val writer = Executors.newSingleThreadExecutor { action ->
        Thread(action, "watchface-shizuku-pipe").apply { isDaemon = true }
    }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "watchface-shizuku-deadline").apply { isDaemon = true }
    }
    private val shell = WatchfaceCacheRootShell(20_000) { command ->
        // The program is regenerated below, never supplied by the Binder caller.
        ProcessBuilder("/system/bin/sh", "-c", command).start()
    }

    override fun uid(): Int = Process.myUid()

    override fun read(kind: String?, bin: String?, relative: String?, budgetMillis: Long): ParcelFileDescriptor {
        if (closed.get()) throw IllegalStateException("SHIZUKU_CLOSED")
        val serviceUid = uid()
        if (serviceUid != 0 && serviceUid != 2000) throw SecurityException("SHIZUKU_UID")
        val request = try {
            require(kind != null && kind.length <= 16)
            require(bin != null && bin.length <= 4096)
            require(relative != null && relative.length <= 4096)
            require(budgetMillis in 1..20_000)
            WatchfaceCacheRequest(kind, bin, relative).also { it.command() }
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("INVALID_REQUEST")
        }
        if (!busy.tryAcquire()) throw IllegalStateException("SHIZUKU_BUSY")
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            synchronized(active) {
                if (closed.get()) throw IllegalStateException("SHIZUKU_CLOSED")
                pipe = ParcelFileDescriptor.createReliablePipe()
                active += pipe!![1]
            }
            val descriptors = pipe!!
            val output = descriptors[1]
            val deadline = System.nanoTime() + budgetMillis * 1_000_000
            val expiry = deadlines.schedule({
                runCatching { output.closeWithError("SHIZUKU_TIMEOUT") }
            }, budgetMillis, TimeUnit.MILLISECONDS)
            try {
                writer.execute {
                    try {
                        // Validate EOF/exit status and both byte limits before publishing any bytes.
                        val bytes = shell.read(
                            WatchfaceCacheRequest.forUid(request.command(), serviceUid),
                            request.limit(), budgetMillis,
                        )
                        writeBounded(output, bytes, deadline)
                        output.close()
                    } catch (error: Exception) {
                        val code = (error as? WatchfaceCacheException)?.code ?: "SHIZUKU_IO"
                        runCatching { output.closeWithError(code) }
                    } finally {
                        expiry.cancel(false)
                        runCatching { output.close() }
                        synchronized(active) { active.remove(output) }
                        busy.release()
                    }
                }
            } catch (error: RuntimeException) {
                expiry.cancel(false)
                throw error
            }
            return descriptors[0]
        } catch (error: Exception) {
            pipe?.forEach { runCatching { it.close() } }
            synchronized(active) { pipe?.let { active.remove(it[1]) } }
            busy.release()
            throw IllegalStateException("SHIZUKU_IO", error)
        }
    }

    /** A caller that never drains its FD cannot retain the service's only worker indefinitely. */
    private fun writeBounded(output: ParcelFileDescriptor, bytes: ByteArray, deadline: Long) {
        val fd = output.fileDescriptor
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLOUT.toShort() }
        var offset = 0
        while (offset < bytes.size) {
            if (closed.get() || Thread.currentThread().isInterrupted) throw WatchfaceCacheException("CANCELLED")
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw WatchfaceCacheException("SHIZUKU_TIMEOUT")
            try {
                val count = Os.write(fd, bytes, offset, minOf(8192, bytes.size - offset))
                if (count <= 0) throw WatchfaceCacheException("SHIZUKU_IO")
                offset += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                Os.poll(arrayOf(poll), minOf(remaining, 100).toInt())
            }
        }
    }

    override fun destroy() {
        if (closed.compareAndSet(false, true)) {
            synchronized(active) {
                active.forEach { runCatching { it.closeWithError("SHIZUKU_CLOSED") } }
                active.clear()
            }
            writer.shutdownNow()
            deadlines.shutdownNow()
        }
        Process.killProcess(Process.myPid())
    }
}
