package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.BandConnectionGate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ClassicBondReportTest {
    private fun uuid(id: String) = "0000$id-0000-1000-8000-00805f9b34fb"

    @Test fun cacheIsDeduplicatedSortedAndBoundedAfterDeduplication() {
        val values = (0..80).map { uuid(it.toString(16).padStart(4, '0')) }
        val report = ClassicBondReport.from(true, "DUAL", (values.reversed() + values).map { it.uppercase() })
        assertEquals(values.take(32), report.uuids)
        assertEquals(32, report.copyText().lines().size)
        assertEquals(report, ClassicBondReport.from(true, "DUAL", values))
    }
    @Test fun knownStandardServicesRemainFullUuidAndClassified() {
        val ids = listOf("1101", "110c", "110e", "110f", "111e", "111f")
        val report = ClassicBondReport.from(true, "DUAL", ids.map(::uuid))
        assertEquals(setOf("SPP", "AVRCP_TARGET", "AVRCP", "AVRCP_CONTROLLER", "HFP", "HFP_AUDIO_GATEWAY"),
            report.standardServices.values.toSet())
        ids.forEach { assertTrue(report.copyText().contains(uuid(it))) }
        assertEquals(ids.map(::uuid).sorted(), report.copyText().lines())
    }
    @Test fun arbitraryInputCannotLeakIdentityAndUnknownIsNotUnsupported() {
        val sensitive = listOf("12:34:56:78:9A:BC", "My private band", "key=00112233445566778899aabbccddeeff")
        val custom = "12345678-1234-1234-1234-123456789abc"
        val report = ClassicBondReport.from(true, sensitive[0], sensitive + custom)
        assertEquals("UNKNOWN", report.deviceType)
        assertEquals(listOf(custom), report.uuids)
        assertTrue(report.standardServices.isEmpty())
        sensitive.forEach { assertFalse((report.diagnostic() + report.copyText()).contains(it)) }
        assertTrue(ClassicBondReport.from(true, "LE", null).uuids.isEmpty())
        assertTrue(ClassicBondReport.from(true, "LE", emptyList()).standardServices.isEmpty())
    }
    private fun failure(snapshot: ClassicTransportSnapshot, phase: ClassicPhase = ClassicPhase.SOCKET_CONNECT) =
        BandConnectionGate.failure(ClassicFailure.capture(phase, IOException("read ret -1"), snapshot))

    @Test fun fast525msFailureWithoutDiscoveryDoesNotImplyRemoteRefusalOrWrongKey() {
        val text = failure(ClassicTransportSnapshot(false, false, 525L))
        listOf("快速建连失败", "未进入认证", "不能据此认定远端拒绝", "无需改AuthKey/ID", "不自动解绑").forEach {
            assertTrue(it, text.contains(it))
        }
    }
    @Test fun fastFailurePolicyHasStrictTimingPhaseAndObservedDiscoveryBoundaries() {
        for (ms in listOf(0L, 999L))
            assertTrue(failure(ClassicTransportSnapshot(false, false, ms)).contains("快速建连失败"))
        for (snapshot in listOf(ClassicTransportSnapshot(false, false, 1000L),
            ClassicTransportSnapshot(false, false, -1L), ClassicTransportSnapshot(false, false, null),
            ClassicTransportSnapshot(null, null, 525L), ClassicTransportSnapshot(true, false, 525L),
            ClassicTransportSnapshot(false, true, 525L)))
            assertFalse(failure(snapshot).contains("快速建连失败"))
        assertFalse(failure(ClassicTransportSnapshot(false, false, 525L), ClassicPhase.AUTH_PROOF).contains("未进入认证"))
    }
}
