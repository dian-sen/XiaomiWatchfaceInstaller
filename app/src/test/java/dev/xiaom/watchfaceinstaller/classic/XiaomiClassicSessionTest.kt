// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.AuthKeyRecord
import dev.xiaom.watchfaceinstaller.ble.XiaomiFrames
import dev.xiaom.watchfaceinstaller.ble.XiaomiProtoWire as P
import dev.xiaom.watchfaceinstaller.ble.XiaomiSessionCrypto as C
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Offline synthetic peer only: never creates an Android adapter or opens a radio. */
class XiaomiClassicSessionTest {
    private val key get() = ByteArray(16) { it.toByte() }
    private val mac = "12:34:56:78:9A:BC"
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun fixture(size: Int = 512) = ByteArray(size) { (it * 31).toByte() }.apply {
        hex("5aa53412").copyInto(this)
        "987654321".toByteArray().copyInto(this, 0x28); this[0x31] = 0
    }
    @Test fun framingAndChecksumKnownVectors() {
        assertArrayEquals(hex("badcfe00c00300000000ef"), XiaomiClassicWire.versionRequest)
        assertArrayEquals(hex("badcfe028005000207020801ef"), XiaomiClassicWire.v1(2, serial = 7, kind = 2, payload = hex("0801")))
        assertArrayEquals(hex("a5a501ff00000000"), XiaomiClassicWire.v2(1, 255))
        assertEquals(0xbb3d, XiaomiClassicWire.crc16("123456789".toByteArray()))
        assertArrayEquals(hex("a5a5030909003dbb313233343536373839"), XiaomiClassicWire.v2(3, 9, "123456789".toByteArray()))
    }
    @Test fun classicCcmAndCtrVectorsDoNotMixNonceSchemes() {
        val phone = ByteArray(16) { (it + 16).toByte() }
        val watch = ByteArray(16) { (it + 32).toByte() }
        val material = C.derive(key, phone, watch)
        val session = XiaomiClassicCrypto(key, phone)
        session.confirm(watch, C.hmac(material.copyOfRange(0, 16), watch + phone), P.phoneInfo(35, "offline", "CN"))
        // Independently generated with OpenSSL AES-128-CTR, key and IV = derived direction key.
        assertArrayEquals(hex("b7e0503c"), session.encryptV2(hex("08041000")))
        assertArrayEquals(hex("0804100032020a00"), session.decryptV2(hex("0d0e04c1008e86ff")))
        val command = hex("08041000")
        assertArrayEquals(C.ccm(true, material.copyOfRange(16, 32), hex("23869a150000000034120000"), command),
            session.encryptV1(command, 0x1234))
        val deviceCipher = C.ccm(true, material.copyOfRange(0, 16), hex("8676d2250000000000000000"), command)
        assertArrayEquals(command, session.decryptV1(deviceCipher))
        deviceCipher[0] = (deviceCipher[0].toInt() xor 1).toByte()
        try { session.decryptV1(deviceCipher); fail("accepted corrupt CCM") }
        catch (_: org.bouncycastle.crypto.InvalidCipherTextException) { }
        session.close()
        try { session.encryptV2(command); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun decoderEverySplitAndCoalescingAndInvalidLength() {
        val a = XiaomiClassicWire.v1(1, serial = 0, kind = 2, payload = ByteArray(100) { it.toByte() })
        val b = XiaomiClassicWire.v2(3, 2, hex("01010801101b"))
        for (cut in 1 until a.size) {
            val d = XiaomiClassicWire.Decoder()
            d.feed(a.copyOfRange(0, cut)); assertNull(d.pop(1))
            d.feed(a.copyOfRange(cut, a.size) + b)
            assertEquals(100, d.pop(1)!!.payload.size)
            assertEquals(2, d.pop(2)!!.serial); assertNull(d.pop(2))
        }
        val d = XiaomiClassicWire.Decoder(); d.feed(hex("badcfe00800200"))
        try { d.pop(1); fail() } catch (_: IllegalArgumentException) { }
        val corrupt = b.copyOf().apply { this[lastIndex] = 0 }
        val e = XiaomiClassicWire.Decoder(); e.feed(corrupt)
        try { e.pop(2); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun keyExportIsIndependentAndClosedRecordRefusesExport() {
        val record = AuthKeyRecord.parse(AuthKeyRecord.MODEL, """{"mac":"12:34:56:78:9a:bc","encrypt_key":"000102030405060708090a0b0c0d0e0f"}""")
        val first = record.copyKeyBytes(); first.fill(0)
        val second = record.copyKeyBytes(); assertArrayEquals(key, second)
        record.close(); assertArrayEquals(key, second); second.fill(0)
        try { record.copyKeyBytes(); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun v1FullEncryptedAuthenticationUploadRefreshAndActivate() = runBlocking {
        exercise(Peer(1), 512)
    }
    @Test fun proto2OmittedZeroSubtypeAndStatusAreNotMistakenForTimeout() = runBlocking {
        exercise(Peer(2).apply { omitDefaultScalars = true }, 512)
    }
    @Test fun versionTimeoutDoesNotGuessV1() = runBlocking {
        val peer = Peer(1).apply { silent = true }
        val session = XiaomiClassicSession(peer, responseTimeoutMs = 30)
        try { session.connectAndAuthenticate(mac, key); fail() } catch (_: TimeoutCancellationException) { }
        assertEquals(XiaomiClassicSession.State.FAILED, session.state.value)
        assertTrue(peer.closed); assertEquals(0, peer.prepareCount)
    }
    @Test fun v2FullFlowFragmentationAckLossNackAndSequenceWrap() = runBlocking {
        val peer = Peer(2).apply { dropFirstAck = true; nackOneUpload = true; duplicateDeviceResponse = true }
        exercise(peer, 17000)
        assertTrue(peer.duplicates >= 2)
        assertTrue(peer.v2Wraps >= 1)
        assertEquals(1, peer.prepareCount)
    }
    private suspend fun exercise(peer: Peer, size: Int) {
        val session = XiaomiClassicSession(peer, responseTimeoutMs = 1000, ackTimeoutMs = 50)
        val secret = key
        session.connectAndAuthenticate(mac, secret)
        assertTrue(secret.all { it == 0.toByte() }); assertEquals(XiaomiClassicSession.State.READY, session.state.value)
        val f = File.createTempFile("classic-offline-", ".bin")
        try {
            val bytes = fixture(size); f.writeBytes(bytes)
            val progress = mutableListOf<XiaomiClassicSession.Progress>()
            val result = session.install(f) { progress += it }
            assertTrue(result.activeConfirmed); assertEquals(peer.version, result.protocolVersion)
            assertEquals(XiaomiClassicSession.State.SUCCEEDED, session.state.value)
            assertArrayEquals(bytes, peer.uploaded)
            assertTrue(peer.listCount >= 4); assertEquals(1, peer.prepareCount)
            assertEquals("设备已确认安装并激活", progress.last().phase)
        } finally { f.delete(); session.close() }
        assertTrue(peer.closed)
    }
    @Test fun unsupportedVersionAndBadHmacFailBeforePrepare() = runBlocking {
        for (peer in listOf(Peer(3), Peer(1).apply { badHmac = true })) {
            val session = XiaomiClassicSession(peer, responseTimeoutMs = 1000, ackTimeoutMs = 30)
            val secret = key
            try { session.connectAndAuthenticate(mac, secret); fail() } catch (_: Exception) { }
            assertEquals(XiaomiClassicSession.State.FAILED, session.state.value)
            assertTrue(secret.all { it == 0.toByte() }); assertTrue(peer.closed); assertEquals(0, peer.prepareCount)
        }
    }
    @Test fun ackTimeoutIsBoundedWithoutReissuingLogicalRequest() = runBlocking {
        val peer = Peer(2).apply { noAcks = true }
        val session = XiaomiClassicSession(peer, responseTimeoutMs = 500, ackTimeoutMs = 20)
        try { session.connectAndAuthenticate(mac, key); fail() } catch (_: Exception) { }
        assertTrue(peer.closed); assertEquals(2, peer.duplicates); assertEquals(0, peer.prepareCount)
    }
    @Test fun noFalseSuccessForExistingIdRejectedPrepareBadMd5ResumeOrMissingActivation() = runBlocking {
        for (mode in 0..5) {
            val peer = Peer(2).apply {
                existing = mode == 0; rejectPrepare = mode == 1; wrongMd5 = mode == 2
                resume = mode == 3; activationDenied = mode == 4; hideInstalled = mode == 5
            }
            val session = XiaomiClassicSession(peer, responseTimeoutMs = 1000, ackTimeoutMs = 50)
            val file = File.createTempFile("classic-negative-", ".bin").apply { writeBytes(fixture()) }
            try {
                session.connectAndAuthenticate(mac, key)
                try { session.install(file); fail("accepted mode $mode") } catch (_: Exception) { }
                assertEquals(XiaomiClassicSession.State.FAILED, session.state.value)
                assertTrue(peer.closed); assertTrue(peer.prepareCount <= 1)
                if (mode < 4) assertNull(peer.uploaded)
            } finally { file.delete(); session.close() }
        }
    }
    @Test fun cancellationClosesPendingReadAndDoesNotSendAgain() = runBlocking {
        val peer = Peer(1).apply { silent = true }
        val session = XiaomiClassicSession(peer)
        val secret = key
        val job = launch { session.connectAndAuthenticate(mac, secret) }
        delay(30); job.cancelAndJoin()
        assertTrue(peer.closed); assertTrue(secret.all { it == 0.toByte() })
        assertEquals(0, peer.prepareCount)
    }

    /** Scripted watch; encrypted command replies and upload envelope are independently verified. */
    private inner class Peer(val version: Int) : ClassicTransport {
        private val raw = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = XiaomiClassicWire.Decoder()
        var closed = false
        var silent = false
        var badHmac = false
        var noAcks = false
        var dropFirstAck = false
        var nackOneUpload = false
        var duplicateDeviceResponse = false
        var existing = false
        var rejectPrepare = false
        var wrongMd5 = false
        var resume = false
        var activationDenied = false
        var hideInstalled = false
        var omitDefaultScalars = false
        var duplicates = 0
        var v2Wraps = 0
        var prepareCount = 0
        var listCount = 0
        var uploaded: ByteArray? = null
        private var negotiated = false
        private var material = byteArrayOf()
        private var encrypted = false
        private var lastPhoneSerial = -1
        private var lastPhoneFrame = byteArrayOf()
        private var deviceSerial = 0
        private var commandCounter = 0
        private var id = "987654321"
        private var active = false
        private var expectedMd5 = byteArrayOf()
        private var expectedSize = 0
        private val upload = ByteArrayOutputStream()
        private var part = 0
        override suspend fun connect(mac: String) { assertEquals(this@XiaomiClassicSessionTest.mac, mac) }
        override suspend fun read(): ByteArray = raw.receive()
        override fun close() { closed = true; raw.close() }
        private fun queue(bytes: ByteArray) {
            // Alternate one-byte prefix and larger coalesced tails, independent of frame boundaries.
            check(raw.trySend(bytes.copyOfRange(0, 1)).isSuccess)
            if (bytes.size > 1) check(raw.trySend(bytes.copyOfRange(1, minOf(4, bytes.size))).isSuccess)
            if (bytes.size > 4) check(raw.trySend(bytes.copyOfRange(4, bytes.size)).isSuccess)
        }
        private fun ctr(directionPhone: Boolean, bytes: ByteArray): ByteArray {
            val k = material.copyOfRange(if (directionPhone) 16 else 0, if (directionPhone) 32 else 16)
            return Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(k)); doFinal(bytes)
            }
        }
        private fun ccm(directionPhone: Boolean, counter: Int, encrypt: Boolean, bytes: ByteArray): ByteArray =
            C.ccm(encrypt, material.copyOfRange(if (directionPhone) 16 else 0, if (directionPhone) 32 else 16),
                C.packetNonce(material.copyOfRange(if (directionPhone) 36 else 32, if (directionPhone) 40 else 36), counter), bytes)
        private fun response(original: ByteArray, forcePlain: Boolean = false) {
            val command = if (!omitDefaultScalars) original else P.parse(original)
                .filterNot { it.number == 2 && it.value == 0L }
                .fold(byteArrayOf()) { acc, f -> acc + if (f.wire == 0) P.uint(f.number, f.value) else P.bytes(f.number, f.bytes) }
            val enc = encrypted && !forcePlain
            val frame = if (version == 1) XiaomiClassicWire.v1(1, serial = deviceSerial, kind = if (enc) 1 else 2,
                payload = if (enc) ccm(false, 0, true, command) else command)
            else XiaomiClassicWire.v2(3, deviceSerial, byteArrayOf(1, if (enc) 2 else 1) + if (enc) ctr(false, command) else command)
            deviceSerial = (deviceSerial + 1) and 255
            queue(if (duplicateDeviceResponse) frame + frame else frame)
        }
        override suspend fun write(bytes: ByteArray) {
            check(!closed)
            if (silent) return
            decoder.feed(bytes)
            val f = decoder.pop(if (negotiated && version == 2) 2 else 1)!!
            if (!negotiated) {
                assertArrayEquals(XiaomiClassicWire.versionRequest, bytes)
                queue(XiaomiClassicWire.v1(0, opcode = 0, serial = 0, kind = 0, payload = byteArrayOf(version.toByte())))
                negotiated = true; return
            }
            if (version == 2 && f.kind == 2) {
                queue(XiaomiClassicWire.v2(2, 0, byteArrayOf(2))); return
            }
            val data: Boolean
            val plain: ByteArray
            if (version == 2) {
                if (f.kind <= 1) return // host ACKs device command
                if (f.serial == lastPhoneSerial) {
                    assertArrayEquals(lastPhoneFrame, bytes); duplicates++
                    if (!noAcks) queue(XiaomiClassicWire.v2(1, f.serial))
                    return
                }
                assertEquals((lastPhoneSerial + 1) and 255, f.serial)
                if (lastPhoneSerial == 255) v2Wraps++
                data = f.payload[0] == 2.toByte()
                if (data && nackOneUpload) {
                    nackOneUpload = false
                    queue(XiaomiClassicWire.v2(0, f.serial)); duplicates++
                    return
                }
                lastPhoneSerial = f.serial; lastPhoneFrame = bytes.copyOf()
                if (dropFirstAck) dropFirstAck = false else if (!noAcks) queue(XiaomiClassicWire.v2(1, f.serial))
                val body = f.payload.copyOfRange(2, f.payload.size)
                plain = if (f.payload[1] == 2.toByte()) ctr(true, body) else body
            } else {
                data = f.channel == 5
                plain = if (f.kind == 1) {
                    val count = if (data) 0 else XiaomiFrames.read16(f.payload)
                    if (!data) assertEquals(++commandCounter, count)
                    ccm(true, count, false, if (data) f.payload else f.payload.copyOfRange(2, f.payload.size))
                } else f.payload
            }
            if (data) { receivePart(plain); return }
            val cmd = P.parse(plain)
            val type = P.one(cmd, 1, 0).value.toInt(); val sub = P.one(cmd, 2, 0).value.toInt()
            when (type to sub) {
                1 to 26 -> {
                    val phone = P.one(P.parse(P.one(P.parse(P.one(cmd, 3, 2).bytes), 30, 2).bytes), 1, 2).bytes
                    val watch = ByteArray(16) { (it + 32).toByte() }
                    material = C.derive(key, phone, watch)
                    val proof = C.hmac(material.copyOfRange(0, 16), watch + phone)
                    if (badHmac) proof[0] = (proof[0].toInt() xor 1).toByte()
                    response(P.command(1, 26, 3, P.bytes(31, P.bytes(1, watch) + P.bytes(2, proof))))
                    phoneNonce = phone
                }
                1 to 27 -> {
                    val proof = P.parse(P.one(P.parse(P.one(cmd, 3, 2).bytes), 32, 2).bytes)
                    assertArrayEquals(C.hmac(material.copyOfRange(16, 32), phoneNonce + ByteArray(16) { (it + 32).toByte() }), P.one(proof, 1, 2).bytes)
                    assertEquals(5, P.parse(ccm(true, 0, false, P.one(proof, 2, 2).bytes)).size)
                    response(P.command(1, 27), forcePlain = true)
                    encrypted = true
                }
                4 to 0 -> {
                    listCount++
                    val faces = if ((uploaded != null && !hideInstalled) || existing) P.bytes(1, P.text(1, id) + P.uint(3, if (active) 1 else 0)) else byteArrayOf()
                    response(P.command(4, 0, 6, P.bytes(1, faces)))
                }
                4 to 4 -> {
                    prepareCount++
                    val p = P.parse(P.one(P.parse(P.one(cmd, 6, 2).bytes), 6, 2).bytes)
                    id = String(P.one(p, 1, 2).bytes); expectedSize = P.one(p, 2, 0).value.toInt()
                    response(P.command(4, 4, 6, (if (omitDefaultScalars && !rejectPrepare) byteArrayOf() else P.uint(5, if (rejectPrepare) 2 else 0))))
                }
                22 to 0 -> {
                    val p = P.parse(P.one(P.parse(P.one(cmd, 24, 2).bytes), 1, 2).bytes)
                    assertEquals(16, P.one(p, 1, 0).value.toInt())
                    expectedMd5 = P.one(p, 2, 2).bytes
                    assertEquals(expectedSize, P.one(p, 3, 0).value.toInt())
                    response(P.command(22, 0, 24, P.bytes(2, P.bytes(1, if (wrongMd5) ByteArray(16) else expectedMd5) +
                        (if (omitDefaultScalars) byteArrayOf() else P.uint(2, 0)) + P.uint(4, if (resume) 1 else 0) + P.uint(5, 64))))
                }
                4 to 1 -> {
                    assertNotNull(uploaded); active = !activationDenied
                    response(P.command(4, 1, 6, P.uint(4, if (activationDenied) 0 else 1)))
                }
                else -> error("Unexpected command $type/$sub")
            }
        }
        private var phoneNonce = byteArrayOf()
        private fun receivePart(bytes: ByteArray) {
            val total = XiaomiFrames.read16(bytes); assertEquals(++part, XiaomiFrames.read16(bytes, 2))
            upload.write(bytes, 4, bytes.size - 4)
            if (part == total) {
                val b = upload.toByteArray()
                assertEquals(0, b[0].toInt()); assertEquals(16, b[1].toInt())
                assertArrayEquals(expectedMd5, b.copyOfRange(2, 18))
                assertEquals(expectedSize, ByteBuffer.wrap(b, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int)
                val data = b.copyOfRange(22, b.size - 4)
                assertEquals(expectedSize, data.size)
                assertArrayEquals(expectedMd5, MessageDigest.getInstance("MD5").digest(data))
                assertEquals(CRC32().apply { update(b, 0, b.size - 4) }.value,
                    ByteBuffer.wrap(b, b.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
                uploaded = data
            }
        }
    }
}
