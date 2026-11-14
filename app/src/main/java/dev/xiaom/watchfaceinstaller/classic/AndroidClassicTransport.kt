// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit secure SPP connection to an already bonded device only. Stops existing local discovery; never starts discovery/fallback/pairing. */
@SuppressLint("MissingPermission")
class AndroidClassicTransport(context: Context) : ClassicTransport {
    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var closed = false
    private var observation = ClassicTransportSnapshot()
    private var socketStartedMs: Long? = null
    override val snapshot: ClassicTransportSnapshot get() = synchronized(lock) {
        socketStartedMs?.let { observation.copy(socketElapsedMs = SystemClock.elapsedRealtime() - it) } ?: observation
    }
    private fun observe(change: (ClassicTransportSnapshot) -> ClassicTransportSnapshot) = synchronized(lock) {
        observation = change(observation)
    }
    private val app = context.applicationContext
    private val workers = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "xiaomi-classic-io").apply { isDaemon = true }
    }
    companion object {
        val SERVICE_UUID: UUID = ClassicProfile.SERVICE_UUID
    }
    private suspend fun <T> io(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        try {
            workers.execute {
                if (continuation.isActive) {
                    try { val result = action(); if (continuation.isActive) continuation.resume(result) }
                    catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    override suspend fun connect(mac: String): Unit = io {
        var phase = ClassicPhase.BONDED_CHECK
        try {
        require(mac.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "无效设备地址" }
        val adapter = checkNotNull(manager?.adapter) { "设备不支持蓝牙" }
        check(adapter.isEnabled) { "请先开启蓝牙" }
        val device = adapter.getRemoteDevice(mac)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在系统中完成配对；本工具不会自动配对" }
        phase = ClassicPhase.DISCOVERY_CANCEL
        // Fail closed even when discovery is currently idle: SCAN is required to query it on S+.
        check(ClassicPermissions.granted(Build.VERSION.SDK_INT) {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }) { "Bluetooth CONNECT and SCAN permissions required to stop local discovery" }
        ClassicDiscovery.stop(
            isDiscovering = { adapter.isDiscovering }, cancel = { adapter.cancelDiscovery() },
            nowMs = { SystemClock.elapsedRealtime() }, pause = { Thread.sleep(it) },
            observe = { before, after -> observe { it.copy(discoveryBefore = before, discoveryAfter = after) } }
        )
        phase = ClassicPhase.SOCKET_CONNECT
        val created = synchronized(lock) {
            check(!closed && socket == null) { "连接已关闭或已使用" }
            device.createRfcommSocketToServiceRecord(SERVICE_UUID).also { socket = it }
        }
        val started = SystemClock.elapsedRealtime()
        synchronized(lock) { socketStartedMs = started }
        try { created.connect() }
        finally { synchronized(lock) {
            observation = observation.copy(socketElapsedMs = SystemClock.elapsedRealtime() - started)
            socketStartedMs = null
        } }
        } catch (e: Exception) { throw ClassicTransportFailure(phase, e, snapshot) }
    }
    override suspend fun read(): ByteArray = io {
        val stream = synchronized(lock) { check(!closed); checkNotNull(socket).inputStream }
        val bytes = ByteArray(8192)
        val count = stream.read(bytes)
        if (count <= 0) throw EOFException("Classic 连接已断开")
        bytes.copyOf(count)
    }
    override suspend fun write(bytes: ByteArray) = io {
        val stream = synchronized(lock) { check(!closed); checkNotNull(socket).outputStream }
        stream.write(bytes)
        stream.flush()
    }
    override fun close() {
        val old = synchronized(lock) { if (closed) return; closed = true; socket.also { socket = null } }
        try { old?.close() } catch (_: Exception) { }
        workers.shutdownNow()
    }
}
