// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from XiaomiAuthService; original copyright/license retained in ProtocolLicense.txt.
package dev.xiaom.watchfaceinstaller.ble

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** RAM-only session; no preferences, logs, intent extras, saved state or key extraction. */
class XiaomiSessionCrypto(secret: ByteArray, phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }) : Closeable {
    private val key = secret.copyOf()
    private val nonce = phoneNonce.copyOf()
    private var material: ByteArray? = null
    private var closed = false
    init { require(key.size == 16 && nonce.size == 16) }

    fun hello(): ByteArray { check(!closed); return XiaomiProtoWire.nonce(nonce) }
    fun confirm(watchNonce: ByteArray, watchHmac: ByteArray, phoneInfo: ByteArray): ByteArray {
        check(!closed && material == null)
        require(watchNonce.size == 16 && watchHmac.size == 32)
        val derived = derive(key, nonce, watchNonce)
        val expected = hmac(derived.copyOfRange(0, 16), watchNonce + nonce)
        if (!MessageDigest.isEqual(expected, watchHmac)) {
            derived.fill(0); expected.fill(0); close()
            error("设备 HMAC 校验失败；拒绝认证")
        }
        expected.fill(0)
        material = derived
        val proof = hmac(derived.copyOfRange(16, 32), nonce + watchNonce)
        val encryptedInfo = encrypt(phoneInfo, 0)
        return XiaomiProtoWire.command(1, 27, 3,
            XiaomiProtoWire.bytes(32, XiaomiProtoWire.bytes(1, proof) + XiaomiProtoWire.bytes(2, encryptedInfo)))
    }
    fun encrypt(plaintext: ByteArray, counter: Int): ByteArray {
        require(counter in 0..65535)
        val m = checkNotNull(material); check(!closed)
        return ccm(true, m.copyOfRange(16, 32), packetNonce(m.copyOfRange(36, 40), counter), plaintext)
    }
    /** BLE-v1 receive uses the device nonce prefix with counter zero, per pinned upstream. */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val m = checkNotNull(material); check(!closed)
        return ccm(false, m.copyOfRange(0, 16), packetNonce(m.copyOfRange(32, 36), 0), ciphertext)
    }
    override fun close() { closed = true; key.fill(0); nonce.fill(0); material?.fill(0); material = null }

    companion object {
        fun parseKey(chars: CharArray): ByteArray {
            try {
                val start = if (chars.size == 34 && chars[0] == '0' && chars[1] == 'x') 2 else 0
                require(chars.size - start == 32) { "AuthKey 必须是 16 字节（32 个十六进制字符）" }
                return ByteArray(16) { i ->
                    val hi = Character.digit(chars[start + i * 2], 16)
                    val lo = Character.digit(chars[start + i * 2 + 1], 16)
                    require(hi >= 0 && lo >= 0) { "AuthKey 含非十六进制字符" }
                    ((hi shl 4) or lo).toByte()
                }
            } finally { chars.fill('\u0000') }
        }
        fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
        }
        fun derive(secret: ByteArray, phone: ByteArray, watch: ByteArray): ByteArray {
            require(secret.size == 16 && phone.size == 16 && watch.size == 16)
            val prk = hmac(phone + watch, secret)
            try {
                val first = hmac(prk, "miwear-auth".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
                val second = hmac(prk, first + "miwear-auth".toByteArray(Charsets.US_ASCII) + byteArrayOf(2))
                return (first + second).also { first.fill(0); second.fill(0) }
            } finally { prk.fill(0) }
        }
        fun packetNonce(prefix: ByteArray, counter: Int): ByteArray {
            require(prefix.size == 4 && counter in 0..65535)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(prefix).putInt(0).putInt(counter).array()
        }
        fun ccm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
            require(key.size == 16 && nonce.size == 12)
            require(encrypt || input.size >= 4)
            val cipher = CCMBlockCipher(AESEngine())
            cipher.init(encrypt, AEADParameters(KeyParameter(key), 32, nonce))
            val output = ByteArray(cipher.getOutputSize(input.size))
            try {
                val n = cipher.processBytes(input, 0, input.size, output, 0)
                val end = cipher.doFinal(output, n)
                return output.copyOf(n + end)
            } finally { output.fill(0); cipher.reset() }
        }
    }
}
