package dev.xiaom.watchfaceinstaller

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AuthKeyPrivacyTest {
    private fun source(name: String): String {
        val relative = "src/main/java/dev/xiaom/watchfaceinstaller/$name.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
    @Test fun clearsOnlyOwnUnchangedClipboard() {
        val lease = SecretClipboardLease()
        val synthetic = CharArray(32) { 'a' }
        var clears = 0
        lease.remember(synthetic)
        assertTrue(lease.clearIfOwned({ CharArray(32) { 'a' }.concatToString() }, { clears++ }))
        assertEquals(1, clears)
        lease.remember(synthetic)
        assertTrue(lease.clearIfOwned({ "user's later text" }, { clears++ }))
        assertEquals(1, clears)
        lease.remember(synthetic)
        assertTrue(lease.clearIfOwned({ null }, { clears++ }))
        assertEquals(1, clears)
        synthetic.fill('\u0000')
    }
    @Test fun comparisonBufferIsCopiedAndForgottenOnEveryExit() {
        val lease = SecretClipboardLease()
        val synthetic = CharArray(32) { 'b' }
        lease.remember(synthetic)
        synthetic.fill('\u0000')
        var clears = 0
        assertTrue(lease.clearIfOwned({ "b".repeat(32) }, { clears++ }))
        assertEquals(1, clears)
        // No retained buffer: read callback must not run after clear.
        assertTrue(lease.clearIfOwned({ error("must not read") }, { error("must not clear") }))
        lease.remember(CharArray(32) { 'c' })
        assertFalse(lease.clearIfOwned({ throw SecurityException() }, { error("must not clear") }))
        assertTrue(lease.clearIfOwned({ error("must not retain after failure") }, {}))
        lease.remember(CharArray(32) { 'd' })
        assertFalse(lease.clearIfOwned({ "d".repeat(32) }, { throw SecurityException() }))
        lease.forget()
    }
    @Test fun temporaryRecordSecretIsErasedOnSuccessAndFailure() {
        val record = AuthKeyRecord.parse(AuthKeyRecord.MODEL,
            """{"encrypt_key":"${"A0".repeat(16)}","mac":"01:23:45:67:89:AB"}""")
        var temporary: CharArray? = null
        record.withSecret { temporary = it; assertEquals(32, it.size) }
        assertTrue(temporary!!.all { it == '\u0000' })
        try { record.withSecret { temporary = it; throw IllegalStateException("synthetic") } }
        catch (_: IllegalStateException) { }
        assertTrue(temporary!!.all { it == '\u0000' })
        val bytes = record.copyKeyBytes()
        assertEquals(16, bytes.size); bytes.fill(0)
        record.close()
        try { record.withSecret { fail("closed record exposed chars") }; fail("closed record accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun privacyWarningAndLifecycleWiring() {
        assertTrue(AuthKeyPrivacy.TIMEOUT_MS in 1..30_000L)
        for (word in listOf("剪贴板", "输入法", "不得分享", "30 秒", "副本无法撤回"))
            assertTrue(word, AuthKeyPrivacy.WARNING.contains(word))
        assertFalse(AuthKeyPrivacy.MASK.contains("A0"))
        val activity = source("AuthKeyActivity")
        assertTrue(activity.contains("显示并复制明文 AuthKey"))
        assertTrue(activity.contains("AlertDialog.Builder"))
        assertTrue(activity.contains("setNegativeButton(\"取消\""))
        assertTrue(activity.contains("setPositiveButton(\"确认风险并复制\""))
        assertTrue(activity.contains("android.content.extra.IS_SENSITIVE"))
        assertTrue(activity.contains("clipboard.setPrimaryClip(clip)"))
        assertTrue(activity.contains("handler.postDelayed(conceal, AuthKeyPrivacy.TIMEOUT_MS)"))
        assertTrue(activity.substringAfter("override fun onStop()").contains("hidePlaintext()"))
        assertTrue(activity.contains("plaintext?.fill('\\u0000'); plaintext = null"))
        for (forbidden in listOf("rememberSaveable", "putExtra(", "putString(", "getSharedPreferences", "Log.", "println("))
            assertFalse(forbidden, activity.contains(forbidden))
    }
}
