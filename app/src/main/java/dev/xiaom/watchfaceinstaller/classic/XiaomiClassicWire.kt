// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Gadgetbridge XiaomiSppPacketV1/V2, © 2023-2024 Yoran Vulker.
// See ProtocolLicense.txt and docs/CLASSIC-NOTICE.md for pinned sources.
package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.ble.XiaomiFrames

internal object XiaomiClassicWire {
    private fun le(n: Int) = XiaomiFrames.le16(n)
    private fun u16(b: ByteArray, p: Int) = XiaomiFrames.read16(b, p)
    data class Frame(val channel: Int = 0, val flags: Int = 0, val opcode: Int = 0,
                     val serial: Int, val kind: Int, val payload: ByteArray)
    fun v1(channel: Int, flags: Int = 0x80, opcode: Int = 2, serial: Int,
           kind: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(channel in 0..15 && serial in 0..255 && payload.size <= 65532)
        return byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), channel.toByte(), flags.toByte()) +
            le(payload.size + 3) + byteArrayOf(opcode.toByte(), serial.toByte(), kind.toByte()) + payload + byteArrayOf(0xef.toByte())
    }
    val versionRequest get() = v1(0, 0xc0, 0, 0, 0)
    fun crc16(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            crc = crc xor (b.toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return crc
    }
    fun v2(kind: Int, serial: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(kind in 0..3 && serial in 0..255 && payload.size <= 65535)
        return byteArrayOf(0xa5.toByte(), 0xa5.toByte(), kind.toByte(), serial.toByte()) +
            le(payload.size) + le(crc16(payload)) + payload
    }
    // Upstream official-app capture. We transmit with window=1 (within advertised capacity).
    val sessionRequest get() = v2(2, 0, byteArrayOf(1, 1, 3, 0, 1, 0, 0,
        2, 2, 0, 0, 0xfc.toByte(), 3, 2, 0, 0x20, 0, 4, 2, 0, 0x10, 0x27))

    /** No guessed delimiter splitting: length-based stream decoder, bounded before allocation.
     * Invalid data closes the session rather than silently swallowing a corrupt auth packet. */
    class Decoder {
        private var buffer = byteArrayOf()
        fun feed(bytes: ByteArray) {
            require(bytes.isNotEmpty() && bytes.size <= 65543 && buffer.size + bytes.size <= 131086) { "SPP 接收缓冲超限" }
            buffer += bytes
        }
        fun pop(version: Int): Frame? {
            require(version in 1..2)
            if (buffer.size < if (version == 1) 7 else 8) return null
            val b = buffer
            fun u(i: Int) = b[i].toInt() and 255
            val size: Int
            val result: Frame
            if (version == 1) {
                require(u(0) == 0xba && u(1) == 0xdc && u(2) == 0xfe) { "SPP V1 帧头错误" }
                val len = u16(b, 5)
                require(len >= 3) { "SPP V1 长度错误" }
                size = len + 8
                if (b.size < size) return null
                require(u(size - 1) == 0xef && u(3) <= 15 && u(4) and 0x3f == 0) { "SPP V1 帧格式错误" }
                result = Frame(u(3), u(4), u(7), u(8), u(9), b.copyOfRange(10, size - 1))
            } else {
                require(u(0) == 0xa5 && u(1) == 0xa5 && u(2) in 0..3) { "SPP V2 帧头错误" }
                size = u16(b, 4) + 8
                if (b.size < size) return null
                val payload = b.copyOfRange(8, size)
                require(crc16(payload) == u16(b, 6)) { "SPP V2 CRC16 校验失败" }
                require(u(2) > 1 || payload.isEmpty()) { "SPP V2 ACK 格式错误" }
                result = Frame(serial = u(3), kind = u(2), payload = payload)
            }
            buffer = b.copyOfRange(size, b.size)
            return result
        }
        fun clear() { buffer.fill(0); buffer = byteArrayOf() }
    }
}
