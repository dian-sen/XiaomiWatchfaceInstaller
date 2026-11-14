// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Gadgetbridge XiaomiCharacteristicV1, XiaomiAuthService and upload/watchface services.
// Original copyright/license retained in ProtocolLicense.txt and docs/BLE-NOTICE.md.
package dev.xiaom.watchfaceinstaller.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

/**
 * Experimental BLE-v1 implementation for OFFLINE REVIEW, not a Band 9 Pro transport.
 * Deliberately not constructed by the activity; firmware/transport selection is not verified.
 * Transport callbacks/CCM verification/device replies, not bytes written, determine completion.
 */
internal class XiaomiBleV1LabSession(private val transport: AndroidBleTransport, scope: CoroutineScope) : Closeable {
    // Fail closed BEFORE launching a notification reader or sending any packet. Removing this
    // gate requires a verified firmware profile and end-to-end protocol/state-machine tests.
    init { XiaomiBleProfile.requireVerifiedBand9ProBle() }
    private val sendLock = Mutex()
    private val commands = Channel<ByteArray>(32)
    private val acks = Channel<Pair<UUID, ByteArray>>(32)
    private var crypto: XiaomiSessionCrypto? = null
    private var authenticated = false
    private var counter = 1
    private var chunksExpected = 0
    private var incomingEncrypted = false
    private var receivedSize = 0
    private val incoming = sortedMapOf<Int, ByteArray>()
    private var chunkDeadline = 0L
    private val reader = scope.launch {
        try {
            for (n in transport.notifications) receive(n)
        } catch (e: Throwable) {
            commands.close(e); acks.close(e); close(); throw e
        }
    }
    private suspend fun receive(n: AndroidBleTransport.Notification) {
        val p = n.bytes
        require(p.size >= 2)
        val chunk = XiaomiFrames.read16(p)
        if (chunk != 0) {
            require(n.characteristic == XiaomiBleProfile.COMMAND_READ && chunksExpected > 0)
            require(System.nanoTime() <= chunkDeadline && chunk in 1..chunksExpected && p.size > 2)
            val body = p.copyOfRange(2, p.size)
            incoming[chunk]?.let { require(it.contentEquals(body)) { "重传片段内容变化" }; return }
            receivedSize += body.size; require(receivedSize <= 1024 * 1024)
            incoming[chunk] = body
            if (incoming.size == chunksExpected) {
                val out = java.io.ByteArrayOutputStream(receivedSize)
                incoming.values.forEach { out.write(it) }
                val plaintext = if (incomingEncrypted) checkNotNull(crypto).decrypt(out.toByteArray()) else out.toByteArray()
                transport.write(n.characteristic, XiaomiFrames.CHUNK_END_ACK)
                incoming.clear(); chunksExpected = 0; receivedSize = 0
                check(commands.trySend(plaintext).isSuccess) { "命令缓冲溢出" }
            }
            return
        }
        require(p.size >= 4)
        when (p[2].toInt() and 255) {
            0 -> {
                require(n.characteristic == XiaomiBleProfile.COMMAND_READ && p.size == 6 && chunksExpected == 0)
                incomingEncrypted = p[3] == 1.toByte()
                require(p[3].toInt() in 0..1 && (!authenticated || incomingEncrypted))
                chunksExpected = XiaomiFrames.read16(p, 4); require(chunksExpected in 1..4096)
                chunkDeadline = System.nanoTime() + 12_000_000_000L
                receivedSize = 0; incoming.clear()
                transport.write(n.characteristic, XiaomiFrames.CHUNK_START_ACK)
            }
            1, 3 -> { check(acks.trySend(n.characteristic to p).isSuccess) { "ACK 缓冲溢出" } }
            2 -> {
                require(n.characteristic == XiaomiBleProfile.COMMAND_READ)
                val encrypted = p[3] == 1.toByte()
                require(p[3].toInt() in 1..2 && (!authenticated || encrypted))
                val raw = p.copyOfRange(4, p.size)
                val plain = if (encrypted) checkNotNull(crypto).decrypt(raw) else raw
                transport.write(n.characteristic, XiaomiFrames.ACK)
                check(commands.trySend(plain).isSuccess)
            }
            else -> error("未知 BLE-v1 分帧类型")
        }
    }
    private suspend fun send(uuid: UUID, plaintext: ByteArray) = sendLock.withLock {
        withTimeout(20_000) {
            val useCounter = uuid != XiaomiBleProfile.UPLOAD
            require(!authenticated || !useCounter || counter <= 65535) { "nonce 序号耗尽，必须重新认证" }
            val index = if (useCounter) counter else 0
            val payload = if (authenticated) checkNotNull(crypto).encrypt(plaintext, index) else plaintext
            val frames = XiaomiFrames.encode(payload, transport.mtu, authenticated, index, useCounter)
            if (authenticated && useCounter) counter++
            transport.write(uuid, frames.start)
            var started = false; var retries = 0
            while (true) {
                val (source, ack) = acks.receive()
                require(source == uuid) { "非当前特征 ACK" }
                if (!frames.chunked) {
                    require(ack.contentEquals(XiaomiFrames.ACK)) { "设备单包 NACK 或错误 ACK" }
                    break
                }
                require(ack[2] == 1.toByte())
                when (ack[3].toInt() and 255) {
                    1 -> {
                        require(!started && ack.size == 4)
                        started = true
                        frames.chunks.forEach { transport.write(uuid, it) }
                    }
                    0 -> { require(started && ack.size == 4); break }
                    5 -> {
                        require(started && ++retries <= 3 && ack.size > 4 && (ack.size - 4) % 2 == 0)
                        for (offset in 4 until ack.size step 2) {
                            val requested = XiaomiFrames.read16(ack, offset)
                            require(requested in 1..frames.chunks.size)
                            transport.write(uuid, frames.chunks[requested - 1])
                        }
                    }
                    else -> error("设备分片 NACK")
                }
            }
        }
    }
    private suspend fun response(type: Int, subtype: Int): List<XiaomiProtoWire.Field> = withTimeout(20_000) {
        repeat(32) {
            val fields = XiaomiProtoWire.parse(commands.receive())
            val status = fields.filter { it.number == 100 }
            if (status.isNotEmpty()) require(XiaomiProtoWire.one(fields, 100, 0).value == 0L) { "设备返回错误状态" }
            if (XiaomiProtoWire.one(fields, 1, 0).value == type.toLong() &&
                XiaomiProtoWire.one(fields, 2, 0).value == subtype.toLong()) return@withTimeout fields
        }
        error("未收到匹配的设备命令回复")
    }
    private fun nested(fields: List<XiaomiProtoWire.Field>, number: Int) =
        XiaomiProtoWire.parse(XiaomiProtoWire.one(fields, number, 2).bytes)

    suspend fun authenticate(key: ByteArray, api: Int, phoneModel: String, region: String) {
        check(!authenticated && crypto == null)
        try {
            crypto = XiaomiSessionCrypto(key)
            for (uuid in listOf(XiaomiBleProfile.COMMAND_WRITE, XiaomiBleProfile.COMMAND_READ, XiaomiBleProfile.UPLOAD)) transport.enableNotifications(uuid)
            send(XiaomiBleProfile.COMMAND_WRITE, checkNotNull(crypto).hello())
            val verify = nested(nested(response(1, 26), 3), 31)
            val command = checkNotNull(crypto).confirm(XiaomiProtoWire.one(verify, 1, 2).bytes,
                XiaomiProtoWire.one(verify, 2, 2).bytes, XiaomiProtoWire.phoneInfo(api, phoneModel, region))
            send(XiaomiBleProfile.COMMAND_WRITE, command)
            val confirmation = nested(nested(response(1, 27), 3), 33)
            // Upstream accepts subtype alone. This lab deliberately requires an explicit positive DeviceConfirm.
            require(XiaomiProtoWire.one(confirmation, 1, 0).value == 1L) { "设备未明确确认认证；停止" }
            authenticated = true; counter = 1
        } catch (e: Throwable) { close(); throw e }
        finally { key.fill(0) }
    }
    private suspend fun watchfaceIds(): Set<String> {
        send(XiaomiBleProfile.COMMAND_WRITE, XiaomiProtoWire.command(4, 0))
        val list = nested(nested(response(4, 0), 6), 1)
        return list.filter { it.number == 1 }.map {
            require(it.wire == 2)
            String(XiaomiProtoWire.one(XiaomiProtoWire.parse(it.bytes), 1, 2).bytes, Charsets.US_ASCII)
        }.toSet()
    }
    data class DeviceConfirmedInstall(val id: String)
    /** Caller must have verified model + hash + explicit consent. Not exposed for Band 9 Pro. */
    suspend fun installNewId(data: ByteArray, id: String, progress: (Int, Int) -> Unit): DeviceConfirmedInstall {
        check(authenticated)
        try {
            return withTimeout(5 * 60_000L) {
                require(data.size in 0xa8..32 * 1024 * 1024)
                require(dev.xiaom.watchfaceinstaller.WatchfaceIdEditor.validatedId(data.copyOfRange(0, 0xa8)) == id)
                require(id !in watchfaceIds()) { "设备已有此 ID；无法区分旧表盘与新安装，拒绝覆盖" }
                send(XiaomiBleProfile.COMMAND_WRITE, XiaomiProtoWire.installStart(id, data.size))
                require(XiaomiProtoWire.one(nested(response(4, 4), 6), 5, 0).value == 0L) { "设备拒绝准备安装" }
                send(XiaomiBleProfile.COMMAND_WRITE, XiaomiProtoWire.uploadStart(data))
                val ack = nested(nested(response(22, 0), 24), 2)
                require(XiaomiProtoWire.one(ack, 2, 0).value == 0L) { "设备拒绝上传" }
                val md5 = ack.filter { it.number == 1 }
                if (md5.isNotEmpty()) require(MessageDigest.isEqual(XiaomiProtoWire.one(ack, 1, 2).bytes, MessageDigest.getInstance("MD5").digest(data)))
                val resume = if (ack.any { it.number == 4 }) XiaomiProtoWire.one(ack, 4, 0).value else 0
                val size = if (ack.any { it.number == 5 }) XiaomiProtoWire.one(ack, 5, 0).value else 2048
                // Resume is not implemented without persisted upload identity; refuse rather than splice wrong data.
                require(resume == 0L) { "非零断点续传未经安全验证，拒绝继续" }
                require(size in 32..65535)
                val parts = XiaomiFrames.uploadParts(data, size.toInt())
                parts.forEachIndexed { index, part -> send(XiaomiBleProfile.UPLOAD, part); progress(index + 1, parts.size) }
                // Transport ACKs are insufficient. A fresh device list must include an initially absent ID.
                var confirmed = false
                repeat(3) { if (!confirmed) { delay(1000); confirmed = id in watchfaceIds() } }
                require(confirmed) { "上传已 ACK，但设备刷新列表未确认新 ID；不能报告安装成功" }
                DeviceConfirmedInstall(id)
            }
        } catch (e: Throwable) { close(); throw e }
    }
    override fun close() {
        authenticated = false; crypto?.close(); crypto = null
        reader.cancel(); commands.close(); acks.close(); transport.close()
        incoming.values.forEach { it.fill(0) }; incoming.clear()
    }
}
