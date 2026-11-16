package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.BandConnectionGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

class ClassicDiscoveryTest {
    @Test fun idleDoesNotCancelOrWait() {
        val values = mutableListOf<Pair<Boolean, Boolean>>()
        ClassicDiscovery.stop({ false }, { error("must not cancel") }, { error("must not wait") },
            { error("must not sleep") }, { before, after -> values += before to after })
        assertEquals(listOf(false to false), values)
    }
    @Test fun runningDiscoveryStopsBeforeProceeding() {
        var now = 0L
        var cancellations = 0
        var reads = 0
        val values = mutableListOf<Pair<Boolean, Boolean>>()
        ClassicDiscovery.stop({ ++reads < 4 }, { cancellations++; true }, { now }, { now += it },
            { before, after -> values += before to after })
        assertEquals(1, cancellations)
        assertEquals(true to false, values.last())
        assertEquals(100L, now)
    }
    @Test fun falseCancelIsFailureEvenIfDiscoveryBecomesIdle() {
        for (after in listOf(true, false)) {
            var first = true
            var observation: Pair<Boolean, Boolean>? = null
            try {
                ClassicDiscovery.stop({ if (first) { first = false; true } else after }, { false },
                    { error("must not wait after rejection") }, { error("must not sleep") },
                    { b, a -> observation = b to a })
                fail("false cancellation silently skipped")
            } catch (e: IOException) { assertTrue(e.message!!.contains("returned false")) }
            assertEquals(true to after, observation)
        }
    }
    @Test fun stuckDiscoveryHasExplicitBoundBelowTwoSeconds() {
        var now = 0L
        var after = false
        try {
            ClassicDiscovery.stop({ true }, { true }, { now }, { now += it }, { _, a -> after = a })
            fail("proceeded with discovery still active")
        } catch (e: SocketTimeoutException) {
            assertEquals(ClassicDiscovery.WAIT_MS, now)
            assertTrue(now < 2_000)
            assertTrue(after)
            val report = ClassicFailure.capture(ClassicPhase.SOCKET_CONNECT,
                ClassicTransportFailure(ClassicPhase.DISCOVERY_CANCEL, e,
                    ClassicTransportSnapshot(true, true)))
            assertEquals(ClassicPhase.DISCOVERY_CANCEL, report.phase)
            assertEquals("Timeout", report.causes.last().type)
            assertTrue(BandConnectionGate.failure(report).contains("未创建连接"))
        }
    }
    @Test fun securityExceptionIsNotSkipped() {
        try {
            ClassicDiscovery.stop({ throw SecurityException("denied") }, { error("no cancel") },
                { 0 }, {}, { _, _ -> })
            fail("permission failure ignored")
        } catch (_: SecurityException) { }
    }
    @Test fun sdkPermissionGateRequiresBothOnSAndLater() {
        for (api in listOf(26, 28, 30)) {
            assertEquals(emptyList<String>(), ClassicPermissions.required(api))
            assertTrue(ClassicPermissions.granted(api) { error("legacy must not ask runtime location/SCAN") })
        }
        for (api in listOf(31, 32, 33, 35, 36)) {
            assertEquals(listOf(ClassicPermissions.CONNECT, ClassicPermissions.SCAN), ClassicPermissions.required(api))
            for (grants in listOf(emptySet(), setOf(ClassicPermissions.CONNECT), setOf(ClassicPermissions.SCAN)))
                assertFalse(ClassicPermissions.granted(api) { it in grants })
            assertTrue(ClassicPermissions.granted(api) { true })
        }
    }
    @Test fun immutableFailureSnapshotAndDistinctIoEofTimeout() {
        var live = ClassicTransportSnapshot(true, false, 123L)
        val report = ClassicFailure.capture(ClassicPhase.SOCKET_CONNECT, IOException("IO read ret -1"), live)
        live = live.copy(socketElapsedMs = 456L)
        assertEquals(123L, report.transport.socketElapsedMs)
        assertEquals(456L, live.socketElapsedMs)
        assertTrue(report.diagnostic().contains("discoveryBefore=true discoveryAfter=false socketElapsedMs=123"))
        assertTrue(BandConnectionGate.failure(report).contains("I/O"))
        assertFalse(BandConnectionGate.failure(report).contains("EOF（"))
        assertTrue(BandConnectionGate.failure(ClassicFailure.capture(ClassicPhase.VERSION_READ, EOFException())).contains("EOF（"))
        assertTrue(BandConnectionGate.failure(ClassicFailure.capture(ClassicPhase.SOCKET_CONNECT, SocketTimeoutException())).contains("超时"))
    }
    @Test fun discoveryFailureRetainedThroughSessionCloseWithoutWriting() = runBlocking {
        val transport = object : ClassicTransport {
            override val snapshot = ClassicTransportSnapshot(true, true)
            override suspend fun connect(mac: String) { throw ClassicTransportFailure(
                ClassicPhase.DISCOVERY_CANCEL, IllegalStateException("cancel false"), snapshot) }
            override suspend fun read(): ByteArray = error("must not read")
            override suspend fun write(bytes: ByteArray) = error("must not write")
            override fun close() { }
        }
        val session = XiaomiClassicSession(transport)
        val key = ByteArray(16) { 1 }
        try { session.connectAndAuthenticate("12:34:56:78:9A:BC", key); fail() }
        catch (_: ClassicTransportFailure) { }
        session.close()
        assertEquals(ClassicPhase.DISCOVERY_CANCEL, session.failure!!.phase)
        assertEquals(true, session.failure!!.transport.discoveryAfter)
        assertNull(session.failure!!.transport.socketElapsedMs)
        assertTrue(key.all { it == 0.toByte() })
    }
    @Test fun firmwareIsOptionalDiagnosticOnlyNotAProfile() {
        assertEquals("未知", ClassicFirmware.diagnostic(""))
        assertEquals("未知", ClassicFirmware.diagnostic("未知"))
        assertEquals("3.1.190", ClassicFirmware.diagnostic(" 3.1.190 "))
        for (s in listOf("12:34:56:78:9A:BC", "key=00112233445566778899aabbccddeeff", "secret text"))
            assertFalse(ClassicFirmware.diagnostic(s).contains(s))
    }
}
