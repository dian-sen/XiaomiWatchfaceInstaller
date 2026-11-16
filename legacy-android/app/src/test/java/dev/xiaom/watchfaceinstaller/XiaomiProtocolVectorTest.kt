package dev.xiaom.watchfaceinstaller

import dev.xiaom.watchfaceinstaller.ble.*
import org.junit.Assert.*
import org.junit.Test

/** Offline vectors only; no Android radio and no assertion of hardware interoperability. */
class XiaomiProtocolVectorTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    @Test fun protobufInstallAndExplicitZeroVectors() {
        assertArrayEquals(hex("080410043210320e0a0931323334353637383910a801"), XiaomiProtoWire.installStart("123456789", 168))
        assertArrayEquals(hex("08041000"), XiaomiProtoWire.command(4, 0))
        assertArrayEquals(hex("08161000c201180a1608101210900150983cd24fb0d6963f7d28e17f721803"), XiaomiProtoWire.uploadStart("abc".toByteArray()))
    }
    @Test fun nonceVectorAndPacketNonceEndian() {
        assertArrayEquals(hex("0801101a1a15f201120a10" + "00".repeat(16)), XiaomiProtoWire.nonce(ByteArray(16)))
        assertArrayEquals(hex("010203040000000034120000"), XiaomiSessionCrypto.packetNonce(hex("01020304"), 0x1234))
    }
    @Test fun hkdfVectorIndependentPythonHmacSha256() {
        assertArrayEquals(hex("d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c9888676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb"),
            XiaomiSessionCrypto.derive(ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 16).toByte() }, ByteArray(16) { (it + 32).toByte() }))
    }
    @Test fun framingVectorsAndProfileDistinction() {
        val one = XiaomiFrames.encode(hex("aabb"), 23, true, 0x1234)
        assertFalse(one.chunked); assertArrayEquals(hex("000002013412aabb"), one.start)
        val plain = XiaomiFrames.encode(hex("aabb"), 23, false, encryptedCharacteristic = false)
        assertTrue(plain.chunked); assertArrayEquals(hex("000000000100"), plain.start)
        assertArrayEquals(hex("0100aabb"), plain.chunks.single())
        val many = XiaomiFrames.encode(ByteArray(20) { it.toByte() }, 23, true, 7)
        assertArrayEquals(hex("000000010200"), many.start)
        assertArrayEquals(hex("01000700000102030405060708090a0b0c0d0e0f"), many.chunks[0])
        assertArrayEquals(hex("020010111213"), many.chunks[1])
        val upload = XiaomiFrames.encode(ByteArray(20) { it.toByte() }, 23, true, incrementNonce = false)
        assertArrayEquals(hex("0100000102030405060708090a0b0c0d0e0f1011"), upload.chunks[0])
    }
    @Test fun uploadEnvelopeMd5Crc32VectorIndependentPython() {
        assertArrayEquals(hex("010001000010900150983cd24fb0d6963f7d28e17f7203000000616263f731aa45"),
            XiaomiFrames.uploadParts("abc".toByteArray(), 64).single())
        val parts = XiaomiFrames.uploadParts(ByteArray(100), 32)
        assertTrue(parts.all { it.size <= 32 })
        parts.forEachIndexed { i, p -> assertEquals(parts.size, XiaomiFrames.read16(p)); assertEquals(i + 1, XiaomiFrames.read16(p, 2)) }
    }
    @Test fun parserRejectsMalformedAmbiguousAndOutOfRangeFields() {
        for (bytes in listOf(hex("00"), hex("0a05ff"), hex("0880"), hex("0b"), ByteArray(11) { 0xff.toByte() }))
            rejected { XiaomiProtoWire.parse(bytes) }
        rejected { XiaomiProtoWire.one(XiaomiProtoWire.parse(hex("08010802")), 1, 0) }
        rejected { XiaomiProtoWire.uint(0, 0) }
        rejected { XiaomiProtoWire.bytes(-1, byteArrayOf()) }
        rejected { XiaomiFrames.encode(byteArrayOf(1), 22, false) }
        rejected { XiaomiFrames.encode(byteArrayOf(1), 23, true, 65536) }
    }
    @Test fun ccmRoundTripRejectsTamperedTagAndBadDeviceHmac() {
        val key = ByteArray(16); val nonce = ByteArray(12); val plain = "offline only".toByteArray()
        val encrypted = XiaomiSessionCrypto.ccm(true, key, nonce, plain)
        assertArrayEquals(plain, XiaomiSessionCrypto.ccm(false, key, nonce, encrypted))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        try { XiaomiSessionCrypto.ccm(false, key, nonce, encrypted); fail("CCM tag accepted") }
        catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {}
        val session = XiaomiSessionCrypto(key, ByteArray(16))
        rejected { session.confirm(ByteArray(16), ByteArray(32), byteArrayOf(1)) }
        rejected { session.hello() }
    }
    @Test fun keyInputClearedOnSuccessAndFailure() {
        val valid = "0x000102030405060708090a0b0c0d0e0f".toCharArray()
        assertArrayEquals(ByteArray(16) { it.toByte() }, XiaomiSessionCrypto.parseKey(valid))
        assertTrue(valid.all { it == '\u0000' })
        val invalid = "not a secret".toCharArray()
        rejected { XiaomiSessionCrypto.parseKey(invalid) }
        assertTrue(invalid.all { it == '\u0000' })
    }
    @Test fun unverifiedFirmwareAlwaysFailsClosed() {
        try { XiaomiBleProfile.requireVerifiedBand9ProBle(); fail("gate open") }
        catch (e: UnsupportedOperationException) { assertEquals(XiaomiBleProfile.BLOCK_REASON, e.message) }
    }
}
