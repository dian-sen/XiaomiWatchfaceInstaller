package dev.xiaom.watchfaceinstaller

/** Pure UI policy: diagnostics never confer authentication or installation readiness. */
object BandConnectionGate {
    private val macPattern = Regex("^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    fun validMac(mac: String?) = mac != null && macPattern.matches(mac)
    fun reason(hasKey: Boolean, mac: String?, bonded: Boolean?): String? = when {
        !hasKey -> "请先读取并选择AuthKey"
        mac == null -> "请选设备"
        !validMac(mac) -> "设备 MAC 格式无效，请重新读取并选择设备"
        bonded == false -> "需系统配对：请开启蓝牙并在系统设置配对所选 MAC"
        else -> null // null bond state means permission must be requested before querying.
    }
    fun canConnect(available: Boolean, hasSession: Boolean, reason: String?) = available && !hasSession && reason == null
    fun canInstall(available: Boolean, hasFile: Boolean, state: String?) = available && hasFile && state == "READY"
    fun failure(error: Throwable, phase: String): String = when {
        error is SecurityException -> "蓝牙连接权限被拒绝或已撤销"
        error is java.io.IOException -> "Classic 连接失败或已断开，请检查系统配对与设备距离"
        error.javaClass.simpleName == "TimeoutCancellationException" -> "设备响应超时，未完成认证"
        phase == "CONNECTING" || phase == "IDLE" -> "Classic 连接失败，未建立安全通道"
        phase == "NEGOTIATING" -> "Classic 协议协商失败，设备响应或协议版本不兼容"
        else -> "认证校验失败，密钥或设备认证响应未通过校验"
    }
}
