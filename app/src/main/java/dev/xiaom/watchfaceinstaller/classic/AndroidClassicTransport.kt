// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit secure SPP connection to an already bonded device only. No discovery/fallback/pairing. */
@SuppressLint("MissingPermission")
class AndroidClassicTransport(context: Context) : ClassicTransport {
    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var closed = false
    private val workers = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "xiaomi-classic-io").apply { isDaemon = true }
    }
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
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
    override suspend fun connect(mac: String) = io {
        require(mac.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "无效设备地址" }
        val adapter = checkNotNull(manager?.adapter) { "设备不支持蓝牙" }
        check(adapter.isEnabled) { "请先开启蓝牙" }
        val device = adapter.getRemoteDevice(mac)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在系统中完成配对；本工具不会自动配对" }
        val created = synchronized(lock) {
            check(!closed && socket == null) { "连接已关闭或已使用" }
            device.createRfcommSocketToServiceRecord(SERVICE_UUID).also { socket = it }
        }
        created.connect()
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
