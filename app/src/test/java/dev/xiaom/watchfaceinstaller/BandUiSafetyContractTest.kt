package dev.xiaom.watchfaceinstaller

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Static wiring regression checks, NOT Activity instrumentation or hardware validation. */
class BandUiSafetyContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/dev/xiaom/watchfaceinstaller/$name.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
    private val band get() = source("BandInstallActivity")
    @Test fun authenticationDoesNotAutomaticallyInstall() {
        val auth = band.substringAfter("private fun authenticate()").substringBefore("private fun confirmInstall()")
        assertTrue(auth.contains("link.connectAndAuthenticate(mac, key)"))
        assertFalse(auth.contains("link.install("))
        assertTrue(auth.contains("key.fill(0)"))
        assertTrue(auth.contains("adapter.bondedDevices"))
        assertFalse(band.contains(".createBond("))
        assertTrue(auth.contains("permission.launch(ClassicPermissions.required(Build.VERSION.SDK_INT).toTypedArray())"))
    }
    @Test fun consentAndRevalidationPrecedeOnlyUploadCall() {
        val flow = band.substringAfter("private fun confirmInstall()").substringBefore("private fun scanDiagnostic()")
        assertTrue(flow.indexOf("InstallConsent.capture") < flow.indexOf("showDialog("))
        assertTrue(flow.indexOf("consent.verify") < flow.indexOf("link.install("))
        assertEquals(1, Regex("link\\.install\\(").findAll(band).count())
        assertTrue(flow.contains("confirmed = result.activeConfirmed"))
        assertFalse(flow.contains("\u0024{p.phase}")) // Progress text must never be interpreted as success.
    }
    @Test fun secretsAreNotSavedAndCleanupIsLifecycleBound() {
        val save = band.substringAfter("override fun onSaveInstanceState").substringBefore("private suspend fun renderSummary")
        assertFalse(save.contains("records")); assertFalse(save.contains("key"))
        assertFalse(band.contains("putByteArray"))
        assertTrue(band.contains("ClipData.newPlainText(\"Classic diagnostic\", diagnosticText())"))
        val diagnostic = band.substringAfter("private fun diagnosticText()").substringBefore("private fun rememberFailure")
        assertFalse(diagnostic.contains("records")); assertFalse(diagnostic.contains("copyKeyBytes"))
        val stop = band.substringAfter("private fun stopFlow()").substringBefore("override fun onDestroy()")
        for (required in listOf("operation?.cancel()", "it.close()", "rootTask?.cancel(true)", "clearKeys()", "override fun onStop()", "stopFlow()"))
            assertTrue(required, stop.contains(required))
        assertTrue(band.contains("records.forEach { it.close() }"))
    }
    @Test fun foregroundAndBusyGateAndSecureCandidatesRemainExplicit() {
        val run = band.substringAfter("private fun launchOperation").substringBefore("private fun editCopy")
        assertTrue(run.contains("if (!foreground || busy || dialog != null) return"))
        assertTrue(run.indexOf("busy = true") < run.indexOf("lifecycleScope.launch"))
        val create = band.substringAfter("override fun onCreate").substringBefore("override fun onStart")
        assertFalse(create.contains("window.addFlags"))
        assertTrue(band.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
        assertFalse(band.contains("BLOCK_REASON"))
    }
    @Test fun connectionGateAndFixedFailureReasonsAreWired() {
        assertTrue(band.contains("connect.isEnabled = BandConnectionGate.canConnect"))
        assertTrue(band.contains("install.isEnabled = BandConnectionGate.canInstall"))
        assertTrue(band.contains("connectionHint.text = when"))
        assertTrue(band.contains("BLE MTU / FE95 仅为诊断，不能解锁安装"))
        val auth = band.substringAfter("private fun authenticate()").substringBefore("private fun confirmInstall()")
        assertTrue(auth.indexOf("connectionBlockReason()") < auth.indexOf("permission.launch"))
        assertTrue(auth.indexOf("BandConnectionGate.validMac(record.mac)") < auth.indexOf("copyKeyBytes()"))
        assertTrue(auth.indexOf("adapter.bondedDevices") < auth.indexOf("copyKeyBytes()"))
        val failure = auth.substringAfter("catch (e: Exception)")
        assertTrue(failure.contains("clearKeys()"))
        assertTrue(failure.contains("BandConnectionGate.failure(report)"))
        assertFalse(failure.contains("e.message"))
        assertTrue(band.substringAfter("private fun stopFlow()").contains("用户取消或页面离开"))
    }
    @Test fun passiveBondedDiagnosticsNeverStartBluetoothCommunication() {
        val passive = band.substringAfter("private fun showBondedSelection()").substringBefore("private fun connectionBlockReason()")
        assertTrue(passive.contains("adapter?.bondedDevices"))
        assertTrue(passive.contains("device.uuids"))
        for (forbidden in listOf(".fetchUuidsWithSdp(", ".startDiscovery(", ".createBond(", ".connect(", "permission.launch("))
            assertFalse(forbidden, passive.contains(forbidden))
        val transport = source("classic/AndroidClassicTransport")
        assertTrue(transport.contains("SERVICE_UUID: UUID = ClassicProfile.SERVICE_UUID"))
        assertTrue(transport.contains("device.createRfcommSocketToServiceRecord(SERVICE_UUID)"))
        assertTrue(transport.contains("BluetoothDevice.BOND_BONDED"))
        for (forbidden in listOf("createInsecure", "getMethod(", "fetchUuidsWithSdp(", "startDiscovery(", "createBond("))
            assertFalse(forbidden, transport.contains(forbidden))
        assertFalse(band.contains("authenticationPhase"))
        assertTrue(band.contains("link.failure ?: ClassicFailure.capture(link.phase, error, link.transportSnapshot)"))
    }
    @Test fun classicDiscoveryStopPermissionsAndDiagnosticsAreWired() {
        val auth = band.substringAfter("private fun authenticate()").substringBefore("private fun confirmInstall()")
        assertTrue(auth.indexOf("if (!classicPermissionsGranted())") < auth.indexOf("val link ="))
        assertTrue(band.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(band.contains("if (classicPermissionsGranted()) authenticate() else"))
        assertTrue(band.contains("SCAN 仅用于停止本机发现，不启动扫描"))
        assertTrue(band.contains("ClassicFirmware.diagnostic(firmware.text.toString())"))
        assertFalse(auth.contains("3.1.190"))
        val transport = source("classic/AndroidClassicTransport")
        assertTrue(transport.indexOf("ClassicDiscovery.stop(") < transport.indexOf("device.createRfcommSocketToServiceRecord("))
        assertTrue(transport.contains("cancel = { adapter.cancelDiscovery() }"))
        assertTrue(transport.contains("ClassicPhase.DISCOVERY_CANCEL"))
        assertTrue(transport.contains("synchronized(lock)"))
        assertTrue(transport.contains("SystemClock.elapsedRealtime() - started"))
        assertFalse(transport.contains(".startDiscovery("))
        assertFalse(transport.contains(".startScan("))
    }
    @Test fun mainEntryStillValidatesPrivateHash() {
        val entry = source("MainActivity").substringAfter("private fun openBandEditor()").substringBefore("private fun rereadCurrentId()")
        assertTrue(entry.contains("WorkingCopyResult.privateFile(filesDir"))
        assertTrue(entry.contains("it.sha256 == current.sha256"))
        assertTrue(entry.contains("WatchfaceXml.validId(checked.id)"))
    }
}
