// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.UUID

object BlePermissions {
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted(context: Context) = required().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

data class BleCandidate(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int, val advertisesXiaomi: Boolean)

/** Scanning is user-triggered. Collect with withTimeout; cancellation ALWAYS stops the scanner. */
@SuppressLint("MissingPermission")
fun scanXiaomiDevices(context: Context): Flow<BleCandidate> = callbackFlow {
    check(BlePermissions.granted(context)) { "缺少蓝牙运行时权限" }
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: error("设备没有蓝牙适配器")
    check(adapter.isEnabled) { "请手动开启蓝牙" }
    val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE 扫描器不可用" }
    val callback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) { close(IOException("BLE 扫描失败：$errorCode")) }
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name ?: "（未广播名称）"
            val hasService = record?.serviceUuids?.contains(ParcelUuid(XiaomiBleProfile.SERVICE)) == true
            if (XiaomiBleProfile.BAND9PRO_NAME.matches(name) || hasService) {
                trySend(BleCandidate(result.device, name, result.device.address, result.rssi, hasService))
            }
        }
    }
    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}

/** One in-flight ATT operation. GATT write completion is NOT a Xiaomi protocol ACK. */
@SuppressLint("MissingPermission")
class AndroidBleTransport(private val context: Context) : Closeable {
    data class Notification(val characteristic: UUID, val bytes: ByteArray)
    private data class Pending(val kind: String, val uuid: UUID?, val reply: CompletableDeferred<Int>)
    private val queue = Mutex()
    @Volatile private var pending: Pending? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    val notifications = Channel<Notification>(64)
    var mtu = 23; private set
    private val connected = CompletableDeferred<Unit>()
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED && !closed) connected.complete(Unit)
            else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IOException("GATT 已断开或失败：$status / $newState"))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete("discover", null, status)
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && value in 23..517) mtu = value
            complete("mtu", null, status)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            complete("cccd", descriptor.characteristic.uuid, status)
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete("write", characteristic.uuid, status)
        @Deprecated("Pre Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = notification(characteristic.uuid, value)
    }
    private fun notification(uuid: UUID, bytes: ByteArray) {
        if (closed) return
        if (bytes.size > 514 || notifications.trySend(Notification(uuid, bytes.copyOf())).isFailure)
            fail(IOException("BLE 通知缓冲溢出，已中止"))
    }
    private fun complete(kind: String, uuid: UUID?, status: Int) {
        val p = pending ?: return
        if (p.kind == kind && p.uuid == uuid) p.reply.complete(status)
    }
    private fun fail(error: Throwable) {
        connected.completeExceptionally(error)
        pending?.reply?.completeExceptionally(error)
        notifications.close(error)
        close()
    }
    private suspend fun operation(kind: String, uuid: UUID? = null, start: () -> Boolean) = queue.withLock {
        check(!closed)
        val p = Pending(kind, uuid, CompletableDeferred())
        check(pending == null); pending = p
        try {
            check(start()) { "GATT 拒绝 $kind" }
            val status = withTimeout(12_000) { p.reply.await() }
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT $kind 状态 $status" }
        } catch (e: Throwable) { close(); throw e }
        finally { pending = null }
    }
    suspend fun connect(device: BluetoothDevice) {
        check(gatt == null && !closed && BlePermissions.granted(context))
        try {
            gatt = checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
            withTimeout(20_000) { connected.await() }
            operation("discover") { checkNotNull(gatt).discoverServices() }
            operation("mtu") { checkNotNull(gatt).requestMtu(512) }
        } catch (e: Throwable) { close(); throw e }
    }
    fun discoveredServices(): Set<UUID> = checkNotNull(gatt).services.map { it.uuid }.toSet()
    fun characteristic(uuid: UUID): BluetoothGattCharacteristic = checkNotNull(
        gatt?.getService(XiaomiBleProfile.SERVICE)?.getCharacteristic(uuid)) { "缺少已验证的 BLE-v1 特征 $uuid" }
    suspend fun enableNotifications(uuid: UUID) {
        val c = characteristic(uuid)
        val value = when {
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> error("特征不支持通知/指示")
        }
        val d = checkNotNull(c.getDescriptor(XiaomiBleProfile.CCCD)) { "缺少 CCCD" }
        operation("cccd", uuid) {
            val g = checkNotNull(gatt)
            check(g.setCharacteristicNotification(c, true))
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") d.value = value; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }
    }
    suspend fun write(uuid: UUID, bytes: ByteArray) {
        require(bytes.size <= minOf(mtu - 3, 512))
        val c = characteristic(uuid)
        val type = when {
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> error("特征不可写")
        }
        operation("write", uuid) {
            val g = checkNotNull(gatt)
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; @Suppress("DEPRECATION") c.value = bytes; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        val error = IOException("BLE 会话已关闭")
        connected.completeExceptionally(error); pending?.reply?.completeExceptionally(error)
        notifications.close(error)
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }; gatt = null
    }
}
