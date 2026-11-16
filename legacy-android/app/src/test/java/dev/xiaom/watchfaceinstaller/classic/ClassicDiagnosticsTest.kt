package dev.xiaom.watchfaceinstaller.classic

import org.junit.Assert.*
import org.junit.Test

class ClassicDiagnosticsTest {
    @Test fun pinnedSppUuidAndSecureSocketRemainStandard() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", ClassicProfile.SERVICE_UUID.toString())
        assertTrue(ClassicProfile.SECURE_SOCKET)
    }
    @Test fun boundedRedactionPreservesIoCauseWithoutSecretsOrControlInjection() {
        val key = "00112233445566778899aabbccddeeff"
        val mac = "AB:CD:12:34:56:78"
        val root = java.io.IOException("read failed, socket might closed or timeout, read ret: -1\n$mac $key key=$key " +
            "00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff\u0000")
        val report = ClassicFailure.capture(ClassicPhase.AUTH_NONCE,
            ClassicTransportFailure(ClassicPhase.SOCKET_CONNECT, root))
        assertEquals(ClassicPhase.SOCKET_CONNECT, report.phase)
        assertEquals(2, report.causes.size)
        val text = report.diagnostic()
        assertTrue(text.contains("read ret: -1"))
        assertFalse(text.contains(mac)); assertFalse(text.contains(key)); assertFalse(text.contains("00 11 22"))
        assertFalse(report.causes.last().message.contains('\n'))
        assertTrue(report.causes.all { it.message.length <= 240 })
        assertEquals("[oversized message omitted]", ClassicRedaction.text("a".repeat(8193)))
        assertTrue(ClassicRedaction.text("z".repeat(500)).length <= 240)
    }
    @Test fun arbitraryParserMessagesAndCyclesAreNotExported() {
        val a = java.io.IOException("read failed")
        val b = IllegalArgumentException("BIN-content-or-key-must-not-leak", a)
        a.initCause(b)
        val report = ClassicFailure.capture(ClassicPhase.VERSION_READ, a)
        assertEquals(2, report.causes.size)
        assertFalse(report.diagnostic().contains("BIN-content"))
    }
}
