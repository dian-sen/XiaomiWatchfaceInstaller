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
        assertTrue(auth.contains("permission.launch(Manifest.permission.BLUETOOTH_CONNECT)"))
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
        assertFalse(band.contains("putByteArray")); assertFalse(band.contains("ClipboardManager"))
        val stop = band.substringAfter("private fun stopFlow()").substringBefore("override fun onDestroy()")
        for (required in listOf("operation?.cancel()", "session?.close()", "rootTask?.cancel(true)", "clearKeys()", "override fun onStop()", "stopFlow()"))
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
    @Test fun mainEntryStillValidatesPrivateHash() {
        val entry = source("MainActivity").substringAfter("private fun openBandEditor()").substringBefore("private fun rereadCurrentId()")
        assertTrue(entry.contains("WorkingCopyResult.privateFile(filesDir"))
        assertTrue(entry.contains("it.sha256 == current.sha256"))
        assertTrue(entry.contains("WatchfaceXml.validId(checked.id)"))
    }
}
