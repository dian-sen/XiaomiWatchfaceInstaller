// SPDX-License-Identifier: AGPL-3.0-or-later
// Protocol adaptation from Gadgetbridge. Original notices: ProtocolLicense.txt and docs/BLE-NOTICE.md.
package dev.xiaom.watchfaceinstaller.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** Pinned BLE-v1 protocol facts, NOT proof that Smart Band 9 Pro uses this transport. */
object XiaomiBleProfile {
    fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    val SERVICE = uuid("fe95")
    val COMMAND_READ = uuid("0051")
    val COMMAND_WRITE = uuid("0052")
    val ACTIVITY = uuid("0053")
    val UPLOAD = uuid("0055")
    val CCCD = uuid("2902")
    val BAND9PRO_NAME = Regex("^Xiaomi Smart Band 9 Pro [0-9A-F]{4}$")
    const val BLOCK_REASON = "Gadgetbridge 的 9 Pro 默认使用 Classic（实验支持）；官方也说明部分小米设备随固件使用 BLE 或 Classic，不能据此断言所有 9 Pro 都不支持 BLE。本 App 尚未完成型号/固件与传输版本确认、认证及安装结果验证，BLE/Classic 直装均关闭；仅提供副本编辑和自愿 BLE 服务诊断。未安装。"
    fun requireVerifiedBand9ProBle(): Nothing = throw UnsupportedOperationException(BLOCK_REASON)
}

/** Minimal bounded proto2 wire codec. Explicit zero fields are serialized, not omitted. */
object XiaomiProtoWire {
    data class Field(val number: Int, val wire: Int, val value: Long = 0, val bytes: ByteArray = byteArrayOf())
    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        var v = value; val out = ByteArrayOutputStream()
        do { val b = (v and 127).toInt(); v = v ushr 7; out.write(b or if (v != 0L) 128 else 0) } while (v != 0L)
        return out.toByteArray()
    }
    private fun tag(field: Int, wire: Int): ByteArray {
        require(field in 1..0x1fffffff) { "非法 protobuf 字段号" }
        return varint((field.toLong() shl 3) or wire.toLong())
    }
    fun uint(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)
    fun bytes(field: Int, value: ByteArray): ByteArray = tag(field, 2) + varint(value.size.toLong()) + value
    fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun float(field: Int, value: Float): ByteArray = tag(field, 5) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    fun parse(data: ByteArray): List<Field> {
        require(data.size <= 1024 * 1024) { "protobuf 超过上限" }
        var cursor = 0
        fun readVarint(): Long {
            var value = 0L
            for (shift in 0..56 step 7) {
                require(cursor < data.size) { "截断的 varint" }
                val b = data[cursor++].toInt() and 255
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
            }
            error("varint 溢出")
        }
        fun read(length: Int): ByteArray {
            require(length >= 0 && length <= data.size - cursor) { "protobuf 长度越界" }
            return data.copyOfRange(cursor, cursor + length).also { cursor += length }
        }
        val fields = mutableListOf<Field>()
        while (cursor < data.size) {
            require(fields.size < 4096)
            val tag = readVarint(); val number = tag ushr 3
            require(number in 1..0x1fffffffL)
            val wire = (tag and 7).toInt()
            fields += when (wire) {
                0 -> Field(number.toInt(), wire, readVarint())
                2 -> { val n = readVarint(); require(n <= Int.MAX_VALUE); Field(number.toInt(), wire, bytes = read(n.toInt())) }
                1 -> Field(number.toInt(), wire, bytes = read(8))
                5 -> Field(number.toInt(), wire, bytes = read(4))
                else -> error("不支持的 protobuf wire type $wire")
            }
        }
        return fields
    }
    fun one(fields: List<Field>, number: Int, wire: Int): Field {
        val found = fields.filter { it.number == number }
        require(found.size == 1 && found[0].wire == wire) { "缺失、重复或错误字段 $number" }
        return found[0]
    }
    fun command(type: Int, subtype: Int, field: Int? = null, body: ByteArray = byteArrayOf()): ByteArray =
        uint(1, type.toLong()) + uint(2, subtype.toLong()) + (if (field != null) bytes(field, body) else byteArrayOf())
    fun nonce(nonce: ByteArray): ByteArray {
        require(nonce.size == 16)
        return command(1, 26, 3, bytes(30, bytes(1, nonce)))
    }
    fun phoneInfo(api: Int, model: String, region: String): ByteArray {
        require(model.length in 1..128 && region.matches(Regex("[A-Z]{2}")))
        return uint(1, 0) + float(2, api.toFloat()) + text(3, model) + uint(4, 224) + text(5, region)
    }
    fun installStart(id: String, size: Int): ByteArray {
        require(id.matches(Regex("(?:[0-9]{9}|[0-9]{12})")) && size > 0)
        return command(4, 4, 6, bytes(6, text(1, id) + uint(2, size.toLong())))
    }
    fun uploadStart(data: ByteArray): ByteArray = command(22, 0, 24,
        bytes(1, uint(1, 16) + bytes(2, MessageDigest.getInstance("MD5").digest(data)) + uint(3, data.size.toLong())))
}

object XiaomiFrames {
    fun le16(value: Int): ByteArray { require(value in 0..65535); return byteArrayOf(value.toByte(), (value ushr 8).toByte()) }
    fun read16(data: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset <= data.size - 2)
        return (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    }
    data class Outgoing(val start: ByteArray, val chunks: List<ByteArray>, val chunked: Boolean)
    /** Ciphertext includes the CCM tag; counter appears only on the phone->device path. */
    fun encode(payload: ByteArray, mtu: Int, encrypted: Boolean, counter: Int = 0,
               incrementNonce: Boolean = true, encryptedCharacteristic: Boolean = true): Outgoing {
        require(mtu in 23..517 && payload.isNotEmpty() && payload.size <= 1024 * 1024)
        require(counter in 0..65535)
        val maximum = minOf(mtu - 3, 512)
        require(encryptedCharacteristic || !encrypted) { "明文特征不可发送密文" }
        // Upstream tests the characteristic profile, not whether authentication has finished.
        // Unencrypted characteristic profiles always use chunked framing, even for one chunk.
        if (encryptedCharacteristic && payload.size + 6 <= maximum) {
            return Outgoing(byteArrayOf(0, 0, 2, if (encrypted) 1 else 2) +
                (if (encrypted) le16(if (incrementNonce) counter else 0) else byteArrayOf()) + payload, emptyList(), false)
        }
        val body = (if (encrypted && incrementNonce) le16(counter) else byteArrayOf()) + payload
        val count = (body.size + maximum - 3) / (maximum - 2)
        require(count in 1..65535)
        val chunks = (0 until count).map { index ->
            le16(index + 1) + body.copyOfRange(index * (maximum - 2), minOf(body.size, (index + 1) * (maximum - 2)))
        }
        return Outgoing(byteArrayOf(0, 0, 0, if (encrypted) 1 else 0) + le16(count), chunks, true)
    }
    val ACK = byteArrayOf(0, 0, 3, 0)
    val CHUNK_START_ACK = byteArrayOf(0, 0, 1, 1)
    val CHUNK_END_ACK = byteArrayOf(0, 0, 1, 0)

    /** Upload envelope CRC32 covers type, MD5, size and resumed content. */
    fun uploadParts(data: ByteArray, chunkSize: Int, resume: Int = 0): List<ByteArray> {
        require(data.size in 1..(32 * 1024 * 1024))
        require(resume in 0..data.size && chunkSize in 32..65535)
        val header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).put(16).put(MessageDigest.getInstance("MD5").digest(data)).putInt(data.size).array()
        val content = header + data.copyOfRange(resume, data.size)
        val crc = CRC32().apply { update(content) }.value
        val body = content + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt()).array()
        val partSize = chunkSize - 4
        val count = (body.size + partSize - 1) / partSize
        require(count <= 65535)
        return (0 until count).map { i -> le16(count) + le16(i + 1) + body.copyOfRange(i * partSize, minOf(body.size, (i + 1) * partSize)) }
    }
}
