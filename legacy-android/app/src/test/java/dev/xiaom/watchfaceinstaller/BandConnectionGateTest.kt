package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test

class BandConnectionGateTest {
    private val mac = "01:23:45:67:89:AB"
    @Test fun missingKeyDeviceAndPairingHaveDistinctHints() {
        assertEquals("请先读取并选择AuthKey", BandConnectionGate.reason(false, null, null))
        assertEquals("请选设备", BandConnectionGate.reason(true, null, null))
        assertTrue(BandConnectionGate.reason(true, "invalid", true)!!.contains("MAC 格式无效"))
        assertTrue(BandConnectionGate.reason(true, mac, false)!!.contains("需系统配对"))
        assertNull(BandConnectionGate.reason(true, mac, true))
        assertNull(BandConnectionGate.reason(true, mac, null)) // Permission request is allowed, not connection yet.
        for (value in listOf("", "01:23:45:67:89", "$mac\n", "01:23:45:67:89:GG"))
            assertFalse(BandConnectionGate.validMac(value))
    }
    @Test fun connectionAndInstallationAreExplicitlyGated() {
        assertTrue(BandConnectionGate.canConnect(true, false, null))
        assertFalse(BandConnectionGate.canConnect(false, false, null))
        assertFalse(BandConnectionGate.canConnect(true, true, null))
        assertFalse(BandConnectionGate.canConnect(true, false, "请先读取并选择AuthKey"))
        for (state in listOf(null, "IDLE", "CONNECTING", "AUTHENTICATING", "CLOSED", "FAILED", "SUCCEEDED", "MTU498 FE95=true"))
            assertFalse(BandConnectionGate.canInstall(true, true, state))
        assertTrue(BandConnectionGate.canInstall(true, true, "READY"))
        assertFalse(BandConnectionGate.canInstall(false, true, "READY"))
        assertFalse(BandConnectionGate.canInstall(true, false, "READY"))
    }
    @Test fun stagesDoNotCollapseIoIntoConnectionFailure() {
        val report = dev.xiaom.watchfaceinstaller.classic.ClassicFailure.capture(
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.AUTH_PROOF, java.io.IOException("read failed"))
        assertTrue(BandConnectionGate.failure(report).contains("AUTH_PROOF"))
        assertFalse(BandConnectionGate.failure(report).contains("安全 RFCOMM"))
    }
}
