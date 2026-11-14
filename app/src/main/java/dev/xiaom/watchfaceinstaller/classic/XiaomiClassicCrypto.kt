// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Gadgetbridge XiaomiAuthService, © 2023-2024 José Rebelo.
// See ProtocolLicense.txt. SPP V1 CCM and SPP V2 CTR are intentionally separate.
package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.ble.XiaomiProtoWire as P
import dev.xiaom.watchfaceinstaller.ble.XiaomiSessionCrypto as C
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class XiaomiClassicCrypto(secret: ByteArray,
    phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }) : Closeable {
    private val secret = secret.copyOf()
    private val phone = phoneNonce.copyOf()
    private var material: ByteArray? = null
    private var closed = false
    init { require(secret.size == 16 && phone.size == 16) }
    fun hello(): ByteArray { check(!closed); return P.nonce(phone) }
    fun confirm(watch: ByteArray, proof: ByteArray, phoneInfo: ByteArray): ByteArray {
        check(!closed && material == null)
        require(watch.size == 16 && proof.size == 32)
        val m = C.derive(secret, phone, watch)
        val expected = C.hmac(m.copyOfRange(0, 16), watch + phone)
        val valid = MessageDigest.isEqual(proof, expected)
        expected.fill(0)
        if (!valid) { m.fill(0); close(); error("设备 HMAC 验证失败，已停止") }
        material = m
        secret.fill(0)
        val confirmation = C.hmac(m.copyOfRange(16, 32), phone + watch)
        return try { P.command(1, 27, 3, P.bytes(32,
            P.bytes(1, confirmation) + P.bytes(2, encryptV1(phoneInfo, 0)))) }
        finally { confirmation.fill(0) }
    }
    private fun ccm(encrypt: Boolean, counter: Int, input: ByteArray): ByteArray {
        check(!closed)
        val m = checkNotNull(material)
        val key = m.copyOfRange(if (encrypt) 16 else 0, if (encrypt) 32 else 16)
        val prefix = m.copyOfRange(if (encrypt) 36 else 32, if (encrypt) 40 else 36)
        return try { C.ccm(encrypt, key, C.packetNonce(prefix, counter), input) }
        finally { key.fill(0); prefix.fill(0) }
    }
    // Verified against SppPacketV1.encode/getDecryptedPayload and AuthService.encrypt/decrypt.
    // Phone commands prefix LE16 counter and use prefix||LE32(0)||LE32(counter).
    // Device V1 and phone upload packets use counter 0, exactly as pinned SPP implementation.
    fun encryptV1(input: ByteArray, counter: Int) = ccm(true, counter, input)
    fun decryptV1(input: ByteArray) = ccm(false, 0, input)
    private fun ctr(encrypt: Boolean, input: ByteArray): ByteArray {
        check(!closed)
        val m = checkNotNull(material)
        val key = m.copyOfRange(if (encrypt) 16 else 0, if (encrypt) 32 else 16)
        return try { Cipher.getInstance("AES/CTR/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(input)
        } } finally { key.fill(0) }
    }
    // Upstream uses IV=key, no per-frame CCM counter or tag for V2. Do not apply BLE nonce here.
    fun encryptV2(input: ByteArray) = ctr(true, input)
    fun decryptV2(input: ByteArray) = ctr(false, input)
    override fun close() { closed = true; secret.fill(0); phone.fill(0); material?.fill(0); material = null }
}
