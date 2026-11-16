package org.zxor.oronbox

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** App-side transport. Binding/permission callbacks use main; blocking reads use the bridge worker. */
internal class WatchfaceCacheShizukuBackend(context: Context, private val main: Handler) : Closeable {
    private val lock = Any()
    private var closed = false
    private var attempt: Binding? = null
    private var permissionReply: ((Result<Any?>) -> Unit)? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context.applicationContext, WatchfaceCacheShizukuService::class.java))
        .daemon(false).processNameSuffix("watchface_cache").tag("watchface-cache-${UUID.randomUUID()}").version(1)
    private val permissionCode = 0x5746
    private val permissionListener: Shizuku.OnRequestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == permissionCode) {
            val reply = synchronized(lock) { permissionReply.also { permissionReply = null } }
            main.removeCallbacks(permissionTimeout)
            Shizuku.removeRequestPermissionResultListener(permissionListenerProxy)
            reply?.invoke(if (grantResult == PackageManager.PERMISSION_GRANTED) Result.success(true)
                else Result.failure(WatchfaceCacheException("SHIZUKU_DENIED")))
        }
    }
    // A property getter avoids referring to a listener from its own initializer.
    private val permissionListenerProxy get() = permissionListener
    private val permissionTimeout = Runnable {
        val reply = synchronized(lock) { permissionReply.also { permissionReply = null } }
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        reply?.invoke(Result.failure(WatchfaceCacheException("SHIZUKU_PERMISSION_TIMEOUT")))
    }

    private inner class Binding : ServiceConnection {
        val ready = CountDownLatch(1)
        var service: IWatchfaceCacheShizukuService? = null
        var failure: Exception? = null
        var bound = false
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) {
                if (!closed && attempt === this) {
                    service = IWatchfaceCacheShizukuService.Stub.asInterface(binder)
                    if (service == null) failure = WatchfaceCacheException("SHIZUKU_UNAVAILABLE")
                }
                ready.countDown()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                service = null
                failure = WatchfaceCacheException("SHIZUKU_DISCONNECTED")
                ready.countDown()
            }
        }
    }

    /** Called on main. Resolves exactly once, including denial, timeout and detach. */
    fun authorize(reply: (Result<Any?>) -> Unit) {
        synchronized(lock) {
            if (closed) { reply(Result.failure(WatchfaceCacheException("CANCELLED"))); return }
            if (permissionReply != null) { reply(Result.failure(WatchfaceCacheException("SHIZUKU_BUSY"))); return }
            try {
                requireAvailable()
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    reply(Result.success(true)); return
                }
                permissionReply = reply
                Shizuku.addRequestPermissionResultListener(permissionListener, main)
                main.postDelayed(permissionTimeout, 60_000)
                Shizuku.requestPermission(permissionCode)
            } catch (error: Exception) {
                permissionReply = null
                main.removeCallbacks(permissionTimeout)
                runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
                reply(Result.failure(mapError(error)))
            }
        }
    }

    fun read(command: String, limit: Long, budgetMillis: Long): ByteArray {
        val request = WatchfaceCacheShizukuRequest.decode(command)
        if (limit != request.limit() || budgetMillis <= 0) throw WatchfaceCacheException("INVALID_REQUEST")
        if (Thread.currentThread().isInterrupted) throw WatchfaceCacheException("CANCELLED")
        val budget = minOf(budgetMillis, 20_000)
        val deadline = System.nanoTime() + budget * 1_000_000
        fun remaining(): Long = ((deadline - System.nanoTime()) / 1_000_000).also {
            if (it <= 0) throw WatchfaceCacheException("SHIZUKU_TIMEOUT")
        }
        val cancelled = AtomicBoolean(false)
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val pump = FutureTask<ByteArray> {
            try {
                val service = connected(remaining())
                val uid = service.uid()
                if (uid != 0 && uid != 2000) throw WatchfaceCacheException("SHIZUKU_UID")
                val fd = service.read(request.kind, request.bin, request.relative, remaining())
                    ?: throw WatchfaceCacheException("SHIZUKU_IO")
                descriptor.set(fd)
                if (cancelled.get()) { fd.close(); throw WatchfaceCacheException("CANCELLED") }
                val bytes = ByteArrayOutputStream()
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size().toLong() + count > limit) throw WatchfaceCacheException("OUTPUT_LIMIT")
                        bytes.write(buffer, 0, count)
                    }
                    fd.checkError() // Reliable-pipe errors must never publish a partial BIN/scan.
                }
                bytes.toByteArray()
            } catch (error: Exception) { throw mapError(error) }
        }
        Thread(pump, "watchface-shizuku-read").apply { isDaemon = true; start() }
        return try {
            pump.get(budget, TimeUnit.MILLISECONDS).also {
                if (Thread.currentThread().isInterrupted) throw WatchfaceCacheException("CANCELLED")
            }
        } catch (error: TimeoutException) {
            throw WatchfaceCacheException("SHIZUKU_TIMEOUT", cause = error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WatchfaceCacheException("CANCELLED", cause = error)
        } catch (error: ExecutionException) {
            throw mapError(error.cause ?: error)
        } finally {
            cancelled.set(true)
            pump.cancel(true)
            descriptor.get()?.let { fd ->
                Thread({ runCatching { fd.close() } }, "watchface-shizuku-close").apply { isDaemon = true; start() }
            }
        }
    }

    private fun connected(budgetMillis: Long): IWatchfaceCacheShizukuService {
        val binding = synchronized(lock) {
            if (closed) throw WatchfaceCacheException("CANCELLED")
            requireAvailable()
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) throw WatchfaceCacheException("SHIZUKU_DENIED")
            attempt ?: Binding().also { next ->
                attempt = next
                if (!main.post {
                    synchronized(lock) bindingStart@{
                        if (closed || attempt !== next) { next.ready.countDown(); return@bindingStart }
                        try {
                            Shizuku.bindUserService(args, next)
                            next.bound = true
                        } catch (error: Exception) {
                            next.failure = mapError(error)
                            next.ready.countDown()
                        }
                    }
                }) {
                    next.failure = WatchfaceCacheException("CANCELLED")
                    next.ready.countDown()
                }
            }
        }
        try {
            if (!binding.ready.await(budgetMillis, TimeUnit.MILLISECONDS)) throw WatchfaceCacheException("SHIZUKU_TIMEOUT")
            return synchronized(lock) {
                if (closed) throw WatchfaceCacheException("CANCELLED")
                binding.failure?.let { throw it }
                binding.service?.takeIf { it.asBinder().isBinderAlive }
                    ?: throw WatchfaceCacheException("SHIZUKU_DISCONNECTED")
            }
        } catch (error: Exception) {
            synchronized(lock) { if (attempt === binding) attempt = null }
            main.post { unbind(binding) }
            throw error
        }
    }

    private fun requireAvailable() {
        if (!Shizuku.pingBinder()) throw WatchfaceCacheException("SHIZUKU_UNAVAILABLE")
        if (Shizuku.isPreV11()) throw WatchfaceCacheException("SHIZUKU_VERSION")
    }

    private fun unbind(binding: Binding) {
        if (binding.bound) {
            runCatching { Shizuku.unbindUserService(args, binding, true) }
            binding.bound = false
        }
    }

    /** Called on main before the bridge cancels its worker. */
    override fun close() {
        val binding: Binding?
        val reply: ((Result<Any?>) -> Unit)?
        synchronized(lock) {
            if (closed) return
            closed = true
            binding = attempt
            attempt = null
            binding?.ready?.countDown()
            reply = permissionReply
            permissionReply = null
        }
        main.removeCallbacks(permissionTimeout)
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        binding?.let(::unbind)
        reply?.invoke(Result.failure(WatchfaceCacheException("CANCELLED")))
    }

    private fun mapError(error: Throwable): WatchfaceCacheException {
        if (error is WatchfaceCacheException) return error
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
            return WatchfaceCacheException("CANCELLED", cause = error)
        }
        val code = when (error) {
            is SecurityException -> "SHIZUKU_DENIED"
            is RemoteException -> "SHIZUKU_DISCONNECTED"
            is IllegalArgumentException -> "INVALID_REQUEST"
            else -> error.message?.substringAfter("Remote error: ")?.takeIf { it in PIPE_ERRORS } ?: "SHIZUKU_IO"
        }
        return WatchfaceCacheException(code, cause = error)
    }

    companion object {
        private val PIPE_ERRORS = setOf(
            "MISSING", "DENIED", "LINK", "ESCAPE", "NOT_REGULAR", "LIMIT", "CHANGED",
            "OUTPUT_LIMIT", "STDERR_LIMIT", "ROOT_STDERR", "ROOT_DENIED", "ROOT_IO", "ROOT_TIMEOUT",
            "ROOT_UNAVAILABLE", "CANCELLED", "SHIZUKU_CLOSED", "SHIZUKU_TIMEOUT", "SHIZUKU_BUSY", "SHIZUKU_IO",
        )
    }
}
