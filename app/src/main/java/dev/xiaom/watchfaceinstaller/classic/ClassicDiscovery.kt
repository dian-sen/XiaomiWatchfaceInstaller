// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

/** Immutable, identity-free transport observations. Unknown means not yet observed, not false. */
data class ClassicTransportSnapshot(
    val discoveryBefore: Boolean? = null,
    val discoveryAfter: Boolean? = null,
    val socketElapsedMs: Long? = null
) {
    fun diagnostic() = "discoveryBefore=$discoveryBefore discoveryAfter=$discoveryAfter socketElapsedMs=$socketElapsedMs"
}

/** No scanning, retries or socket fallback. Separate pure policy allows deterministic JVM tests. */
object ClassicDiscovery {
    const val WAIT_MS = 1_500L
    fun stop(
        isDiscovering: () -> Boolean,
        cancel: () -> Boolean,
        nowMs: () -> Long,
        pause: (Long) -> Unit,
        observe: (Boolean, Boolean) -> Unit
    ) {
        val before = isDiscovering()
        observe(before, before)
        if (!before) return
        val accepted = cancel()
        var after = isDiscovering()
        observe(before, after)
        if (!accepted) throw java.io.IOException("Bluetooth discovery cancellation returned false")
        val deadline = nowMs() + WAIT_MS
        while (after) {
            val remaining = deadline - nowMs()
            if (remaining <= 0) throw java.net.SocketTimeoutException("Bluetooth discovery cancellation timed out")
            pause(minOf(50L, remaining))
            after = isDiscovering()
            observe(before, after)
        }
    }
}

/** API 26–30 use manifest BLUETOOTH/ADMIN, not location permission for stopping discovery. */
object ClassicPermissions {
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    fun required(api: Int): List<String> = if (api >= 31) listOf(CONNECT, SCAN) else emptyList()
    fun granted(api: Int, hasPermission: (String) -> Boolean) = required(api).all(hasPermission)
}

/** Accept only version-shaped text for export; arbitrary user text may contain identifiers/secrets. */
object ClassicFirmware {
    fun diagnostic(input: String): String {
        val value = input.trim()
        return when {
            value.isEmpty() || value == "未知" -> "未知"
            value.matches(Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,4}")) -> value
            else -> "用户已填写（非版本格式已省略）"
        }
    }
}
