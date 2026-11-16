package org.zxor.oronbox

import android.app.Activity
import android.view.WindowManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Root snapshots stay native; Dart sees opaque selectors until explicit import. */
internal class DeviceKeyBridge(private val activity: Activity, messenger: BinaryMessenger) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val records = linkedMapOf<String, AuthKeyRecord>()
    private val channel = MethodChannel(messenger, "watchface/device_keys")
    @Volatile private var closed = false
    private var operation: java.util.concurrent.Future<*>? = null
    private var pendingResult: MethodChannel.Result? = null
    private var protected = false
    private val wasProtected = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "protect" -> {
                    protected = true
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    result.success(null)
                }
                "release" -> {
                    clear()
                    protected = false
                    if (!wasProtected) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    result.success(null)
                }
                "clear" -> { clear(); result.success(null) }
                "readRoot" -> {
                    clear()
                    val request = generation.get()
                    pendingResult = result
                    operation = executor.submit {
                        try {
                            val found = RootAuthKeySource(activity.applicationContext).read()
                            activity.runOnUiThread {
                                if (closed || request != generation.get()) {
                                    found.forEach { it.close() }
                                    if (pendingResult === result) {
                                        pendingResult = null
                                        result.error("KEY_REQUEST_EXPIRED", "", null)
                                    }
                                } else {
                                    pendingResult = null
                                    operation = null
                                    val choices = found.map { record ->
                                        val token = UUID.randomUUID().toString()
                                        records[token] = record
                                        mapOf("token" to token, "model" to record.model, "mac" to record.mac)
                                    }
                                    result.success(choices)
                                }
                            }
                        } catch (_: Exception) {
                            activity.runOnUiThread {
                                if (pendingResult === result) {
                                    pendingResult = null
                                    operation = null
                                    result.error("ROOT_KEY_READ_FAILED", "", null)
                                }
                            }
                        }
                    }
                }
                "consume" -> {
                    val token = call.argument<String>("token")
                    val record = records.remove(token)
                    if (record == null) result.error("KEY_REQUEST_EXPIRED", "", null)
                    else try {
                        val value = record.withSecret { chars ->
                            mapOf("model" to record.model, "mac" to record.mac, "authKey" to String(chars))
                        }
                        result.success(value)
                    } finally { record.close(); clear() }
                }
                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        pendingResult?.error("KEY_REQUEST_EXPIRED", "", null)
        pendingResult = null
        operation?.cancel(true)
        operation = null
        records.values.forEach { it.close() }
        records.clear()
    }

    fun close() {
        closed = true
        clear()
        channel.setMethodCallHandler(null)
        if (protected && !wasProtected) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        executor.shutdownNow()
    }
}
