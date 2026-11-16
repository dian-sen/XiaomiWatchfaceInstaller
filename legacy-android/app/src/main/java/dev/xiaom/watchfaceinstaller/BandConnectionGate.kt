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
    fun failure(report: dev.xiaom.watchfaceinstaller.classic.ClassicFailure): String {
        val phase = report.phase
        val detail = when (phase) {
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.BONDED_CHECK -> "系统配对或蓝牙权限检查失败"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.DISCOVERY_CANCEL -> "停止本机蓝牙发现失败；未创建连接，不会跳过此步骤"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.SOCKET_CONNECT -> "安全 RFCOMM / SDP 连接失败或已断开；尚未进行密钥认证"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.VERSION_WRITE -> "SPP 版本请求写入失败"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.VERSION_READ -> "SPP 版本响应读取失败；不是密钥选择错误"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.AUTH_NONCE -> "认证随机数交换失败"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.AUTH_PROOF -> "认证证明交换或校验失败"
            dev.xiaom.watchfaceinstaller.classic.ClassicPhase.LIST_CONFIRM -> "加密列表往返确认失败"
            else -> "Classic 协议操作失败"
        }
        val kind = when {
            report.causes.any { it.type == "Timeout" } -> "超时"
            report.causes.any { it.type == "EOFException" } -> "EOF（连接流已结束）"
            report.causes.any { it.type == "IOException" } -> "I/O 失败（不据 read -1 推断固件传输类型）"
            else -> "操作失败"
        }
        val snapshot = report.transport
        val fast = phase == dev.xiaom.watchfaceinstaller.classic.ClassicPhase.SOCKET_CONNECT &&
            snapshot.socketElapsedMs?.let { it >= 0 && it < 1_000 } == true &&
            snapshot.discoveryBefore == false && snapshot.discoveryAfter == false
        val guidance = if (fast)
            "快速建连失败，未进入认证，不能据此认定远端拒绝；无需改AuthKey/ID；不自动解绑。"
        else "不依据单次连接错误推断传输类型；不自动解绑或重连。"
        return "$phase：$detail\n$kind\n$guidance\n${report.diagnostic()}"
    }
}
