package dev.xiaom.watchfaceinstaller

/** Pure policy; Android clipboard objects and their immutable text cannot be zeroed by this app. */
object AuthKeyPrivacy {
    const val TIMEOUT_MS = 30_000L
    const val MASK = "AuthKey：••••••••••••••••••••••••••••••••（32 位十六进制）"
    const val WARNING = "明文密钥可被剪贴板、输入法或其他获准应用读取，不得分享。确认后最多显示 30 秒，离开页面立即隐藏；仅当剪贴板仍为本次密钥时尝试清除。系统或其他应用已取得的副本无法撤回。"
}

/** Owns only a temporary, erasable comparison buffer. Never uses clipboard coercion/URI loading. */
class SecretClipboardLease {
    private var expected: CharArray? = null
    fun remember(secret: CharArray) {
        forget()
        expected = secret.copyOf()
    }
    fun clearIfOwned(read: () -> CharSequence?, clear: () -> Unit): Boolean {
        val value = expected ?: return true
        return try {
            val current = read()
            if (current != null && current.length == value.size && value.indices.all { current[it] == value[it] }) clear()
            true
        } catch (_: Exception) { false }
        finally { forget() }
    }
    fun forget() { expected?.fill('\u0000'); expected = null }
}
