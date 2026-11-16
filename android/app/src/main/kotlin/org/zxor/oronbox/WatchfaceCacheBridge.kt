package org.zxor.oronbox

import android.content.Context
import android.os.Handler
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/** One reader/executor per engine attachment; channel registration and close run on the main thread. */
internal class WatchfaceCacheBridge(
    private val channel: MethodChannel,
    private val reader: RootWatchfaceCacheReader,
    private val executor: ExecutorService,
    private val postResult: (Runnable) -> Boolean,
    private val clearResults: () -> Unit,
    private val onCleanupError: (Exception) -> Unit,
    private val selectMode: (String) -> Unit = { mode ->
        if (mode != "root") throw WatchfaceCacheException("SHIZUKU_UNAVAILABLE")
    },
    private val authorizeShizuku: (((Result<Any?>) -> Unit) -> Unit)? = null,
    private val closeBackend: () -> Unit = {},
    private val authorizeRoot: () -> Boolean = { WatchfaceCacheRootShell().authorize() },
) : Closeable {
    private class Pending(val result: MethodChannel.Result, var task: FutureTask<Unit>? = null)
    private sealed class Reply {
        class Success(val value: Any?) : Reply()
        class Error(val code: String, val message: String?) : Reply()
        object NotImplemented : Reply()
    }
    private val lock = Any()
    private val pending = linkedMapOf<Long, Pending>()
    private var nextRequest = 0L
    private var closed = false

    init {
        channel.setMethodCallHandler(::onMethodCall)
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val id = synchronized(lock) {
            if (closed) return
            (++nextRequest).also { pending[it] = Pending(result) }
        }
        when (call.method) {
            "scan" -> {
                val rawMode = (call.arguments as? Map<*, *>)?.get("mode")
                val mode = rawMode ?: "root"
                if (mode != "root" && mode != "shizuku") {
                    finish(id, Reply.Error("INVALID_ARGUMENT", "mode must be root or shizuku"))
                } else submit(id) { selectMode(mode as String); reader.scan().toMap() }
            }
            "authorize" -> {
                val mode = (call.arguments as? Map<*, *>)?.get("mode") ?: "root"
                when (mode) {
                    "root" -> submit(id) { authorizeRoot() }
                    "shizuku" -> {
                        val authorize = authorizeShizuku
                        if (authorize == null) finish(id, Reply.Error("SHIZUKU_UNAVAILABLE", "Shizuku context not attached"))
                        else try {
                            authorize { outcome ->
                                outcome.fold(
                                    { finish(id, Reply.Success(it)) },
                                    { error -> finish(id, Reply.Error((error as? WatchfaceCacheException)?.code ?: "SHIZUKU_IO", error.message)) },
                                )
                            }
                        } catch (error: Exception) {
                            finish(id, Reply.Error((error as? WatchfaceCacheException)?.code ?: "SHIZUKU_IO", error.message))
                        }
                    }
                    else -> finish(id, Reply.Error("INVALID_ARGUMENT", "mode must be root or shizuku"))
                }
            }
            "copyBundle", "copy" -> {
                val token = stringArgument(call, "token", 128)
                if (token == null) finish(id, Reply.Error("INVALID_ARGUMENT", "token is required"))
                else submit(id) {
                    if (call.method == "copyBundle") reader.copyBundle(token) else reader.copyToPrivateCache(token)
                }
            }
            "deleteCopy" -> {
                val path = stringArgument(call, "path", 4096)
                if (path == null) finish(id, Reply.Error("INVALID_ARGUMENT", "path is required"))
                else submit(id) { reader.cleanupBundle(path); null }
            }
            else -> finish(id, Reply.NotImplemented)
        }
    }

    private fun stringArgument(call: MethodCall, key: String, limit: Int): String? =
        ((call.arguments as? Map<*, *>)?.get(key) as? String)?.takeIf {
            it.isNotBlank() && it.length <= limit && '\u0000' !in it
        }

    private fun submit(id: Long, operation: () -> Any?) {
        synchronized(lock) {
            val request = pending[id] ?: return
            if (closed) return
            val task = FutureTask<Unit> {
                try {
                    finish(id, Reply.Success(operation()))
                } catch (error: Exception) {
                    val code = when {
                        error is WatchfaceCacheException -> error.code
                        error is InterruptedException || Thread.currentThread().isInterrupted -> "CANCELLED"
                        else -> "WATCHFACE_CACHE_FAILED"
                    }
                    finish(id, Reply.Error(code, error.message))
                }
            }
            request.task = task
            try {
                executor.execute(task)
            } catch (_: RejectedExecutionException) {
                finish(id, Reply.Error("CANCELLED", "Cache worker is closed"))
            }
        }
    }

    private fun finish(id: Long, reply: Reply) {
        synchronized(lock) {
            if (closed || id !in pending) return
            // Posted callbacks carry an ID, never a Flutter Result. close() releases every Result immediately.
            val delivery = Runnable {
                synchronized(lock) delivery@{
                    val request = pending.remove(id) ?: return@delivery
                    if (!closed) when (reply) {
                        is Reply.Success -> request.result.success(reply.value)
                        is Reply.Error -> request.result.error(reply.code, reply.message, null)
                        Reply.NotImplemented -> request.result.notImplemented()
                    }
                }
            }
            if (!postResult(delivery)) {
                pending.remove(id)?.result?.let { result ->
                    runCatching { result.error("CANCELLED", "Main thread unavailable", null) }
                }
            }
        }
    }

    /** Cancel operations now; enqueue local-only cleanup behind them, then terminate the same worker. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel.setMethodCallHandler(null)
            val abandoned = pending.values.toList()
            abandoned.forEach { it.task?.cancel(true) }
            pending.clear()
            runCatching(clearResults)
            runCatching(closeBackend).onFailure { error ->
                if (error is Exception) onCleanupError(error)
            }
            abandoned.forEach { request ->
                runCatching { request.result.error("CANCELLED", "Cache bridge closed", null) }
            }
            try {
                executor.execute {
                    Thread.interrupted()
                    try { reader.close() } catch (error: Exception) { onCleanupError(error) }
                }
            } catch (error: RejectedExecutionException) {
                onCleanupError(error)
            } finally {
                executor.shutdown()
            }
        }
    }

    companion object {
        fun attach(
            messenger: BinaryMessenger, cacheDir: File, mainHandler: Handler, context: Context? = null,
        ): WatchfaceCacheBridge {
            val bridgeHandler = Handler(mainHandler.looper)
            val backend = context?.let { WatchfaceCacheShizukuBackend(it, bridgeHandler) }
            val shell = WatchfaceCacheRootShell()
            var mode = "root" // Accessed only on the single cache worker.
            val reader = RootWatchfaceCacheReader(cacheDir, shell) { command, limit, budget ->
                if (mode == "root") shell.read(command, limit, budget)
                else (backend ?: throw WatchfaceCacheException("SHIZUKU_UNAVAILABLE")).read(command, limit, budget)
            }
            return WatchfaceCacheBridge(
                MethodChannel(messenger, PlatformChannelNames.WATCHFACE_CACHE),
                reader,
                Executors.newSingleThreadExecutor { action -> Thread(action, "oronbox-watchface-cache") },
                { action -> bridgeHandler.post(action) },
                { bridgeHandler.removeCallbacksAndMessages(null) },
                { error -> Log.w("WatchfaceCacheBridge", "Private cache cleanup failed", error) },
                { selected -> mode = selected },
                backend?.let { transport -> { reply -> transport.authorize(reply) } },
                { backend?.close() },
                { shell.authorize() },
            )
        }
    }
}
