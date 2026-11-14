// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.xiaom.watchfaceinstaller.classic.*
import dev.xiaom.watchfaceinstaller.ble.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Internal entry: private working copies only. Secrets are RAM-only, never Activity results/state. */
class BandInstallActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PRIVATE_PATH = "private_path"
        const val EXTRA_RESULT_ID = "result_id"
        const val EXTRA_RESULT_SHA256 = "result_sha256"
        const val EXTRA_RESULT_INSTALLED = "result_installed"
        private const val STATE_WORK_PATH = "work_path"
        private const val STATE_SUBMITTED = "install_submitted"
        private const val STATE_CONFIRMED = "install_confirmed"
    }
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var connectionHint: TextView
    private lateinit var sessionState: TextView
    private lateinit var bondedInfo: TextView
    private lateinit var firmware: EditText
    private var attemptedFirmware = "未知"
    private var deviceDiagnostic = "selected-bonded-device=unknown"
    private var lastDiagnostic = "尚未尝试连接；无失败诊断。"
    private var lastClassicPhase = ClassicPhase.IDLE
    private fun diagnosticText() = "Classic diagnostic (redacted)\n" +
        "service=${ClassicProfile.SERVICE_UUID} secure=true SDP=UUID\n" +
        "firmware(user-provided; diagnostic-only)=$attemptedFirmware\n$deviceDiagnostic\n$lastDiagnostic\n无 key、完整 MAC、BIN 内容；不扫描、不自动重连。"
    private fun rememberFailure(link: XiaomiClassicSession, error: Throwable): ClassicFailure {
        val report = link.failure ?: ClassicFailure.capture(link.phase, error, link.transportSnapshot)
        lastClassicPhase = report.phase
        lastDiagnostic = report.diagnostic()
        return report
    }
    private lateinit var choices: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var edit: Button
    private lateinit var back: Button
    private lateinit var rootRead: Button
    private lateinit var connect: Button
    private lateinit var install: Button
    private lateinit var blePanel: LinearLayout
    private lateinit var bleScan: Button
    private lateinit var bleDevices: LinearLayout
    private var bleLink: AndroidBleTransport? = null
    private var pendingBlePermission = false
    private lateinit var newId: EditText
    private var workFile: File? = null
    private var initialized = false
    private var foreground = false
    private var busy = false
    private var operation: Job? = null
    private var observer: Job? = null
    private var session: XiaomiClassicSession? = null
    private var dialog: AlertDialog? = null
    private var pendingPermission = false
    private var submitted = false
    private var confirmed = false
    private val secretLock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private var rootTask: Future<*>? = null
    private var epoch = 0
    private var records: List<AuthKeyRecord> = emptyList()
    private var selected: Int? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val resume = pendingPermission && foreground
        pendingPermission = false
        if (resume) {
            busy = false
            if (classicPermissionsGranted()) authenticate() else { status.text = "蓝牙 CONNECT + SCAN 权限未完整获准；SCAN 仅用于停止本机发现，不启动扫描。未连接、未安装，不会跳过。"; refresh() }
        }
    }

    private val blePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val resume = pendingBlePermission && foreground
        pendingBlePermission = false
        if (resume) {
            busy = false
            if (BlePermissions.granted(this)) scanDiagnostic()
            else { status.text = "BLE 诊断权限未获准；未扫描。"; refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        submitted = savedInstanceState?.getBoolean(STATE_SUBMITTED) ?: false
        confirmed = savedInstanceState?.getBoolean(STATE_CONFIRMED) ?: false
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, 10, 0, 10); content.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; content.addView(this)
        }
        label("小米手环 9 Pro · 实验性 Classic 安装")
        label("尚未真机验证，机型兼容性未保证。流程：私有 BIN → 可选改 ID → Root 读取 n67cn 密钥 → 手动选设备 → 连接并认证 → 单独确认安装。必须先在系统蓝牙中完成配对，本应用不自动配对。")
        label("修改 ID 不绕过签名或校验。认证 READY 仅表示连接就绪；上传进度不是安装成功。离开页面立即取消、清密钥并关闭连接，不自动重连或重刷。")
        summary = label("正在验证私有工作副本…").apply { setTextIsSelectable(true) }
        status = label("未读取密钥，未连接，未安装。")
        sessionState = label("Classic：IDLE（尚未连接）")
        bondedInfo = label("所选系统配对设备：尚未选择。仅查询系统缓存，不发起发现 / SDP 刷新。")
        button("复制脱敏诊断") {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Classic diagnostic", diagnosticText()))
            Toast.makeText(this, "已复制脱敏诊断（无密钥、完整 MAC 或 BIN 内容）", Toast.LENGTH_SHORT).show()
        }
        label("连接前会停止本机已有的 Bluetooth discovery；Android 12+ 需要 CONNECT + SCAN 权限。此连接流程不启动扫描，拒绝任一权限将停止连接。停止发现不是连接已修复的证明。")
        label("固件（用户可选填写，例如 3.1.190；默认未知）。仅供诊断，不用于选择传输或固件 profile。")
        firmware = EditText(this).apply {
            hint = "未知（可选固件版本）"
            isSaveEnabled = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            content.addView(this)
        }
        connectionHint = label("请先读取并选择AuthKey")
        progress = ProgressBar(this).also { content.addView(it) }
        newId = EditText(this).apply {
            hint = "新 ID（保持当前 9 或 12 位长度）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(12)); content.addView(this)
        }
        edit = button("生成改 ID 副本（不覆盖源文件）", ::editCopy)
        back = button("返回当前副本，交主界面导出", ::returnCopy)
        rootRead = button("Root读取设备密钥（仅 n67cn）", ::readKeys)
        label("只读小米运动健康数据库私有临时快照；候选展示 MAC，AuthKey 始终掩码。密钥不保存、不复制、不跨页面传递。读取后必须手动选一条。")
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false; content.addView(this) }
        connect = button("连接并认证（不安装）", ::authenticate)
        install = button("安装当前私有 BIN（需二次确认）", ::confirmInstall)
        button("取消 / 断开 / 清空密钥") { stopFlow(); status.text = stopMessage() }
        blePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        blePanel.addView(TextView(this).apply {
            text = "独立 BLE 诊断：只扫描、发现服务及协商 MTU，不认证、不上传，不能替代 Classic 认证。广播名和 FE95 不证明型号兼容。"
        })
        bleScan = Button(this).apply { text = "扫描 BLE（15 秒，仅诊断）"; setOnClickListener { scanDiagnostic() } }
        blePanel.addView(bleScan)
        bleDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blePanel.addView(bleDevices)
        button("展开 / 收起独立 BLE 诊断") {
            blePanel.visibility = if (blePanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        content.addView(blePanel)
        restoredPath = savedInstanceState?.getString(STATE_WORK_PATH) ?: intent.getStringExtra(EXTRA_PRIVATE_PATH)
        refresh()
    }
    private var restoredPath: String? = null
    override fun onStart() {
        super.onStart(); synchronized(secretLock) { foreground = true }
        if (!initialized) {
            initialized = true
            launchOperation {
                val path = restoredPath ?: error("缺少私有工作副本路径")
                workFile = withContext(Dispatchers.IO) { privateSnapshot(path) }
                renderSummary()
                status.text = if (submitted) stopMessage() else "私有工作副本已验证。可改 ID、返回导出，或显式开始实验性 Classic 认证。"
            }
        }
        refresh()
    }
    private fun refresh() {
        val enabled = foreground && !busy && dialog == null
        edit.isEnabled = enabled && workFile != null && session == null
        back.isEnabled = enabled && workFile != null
        newId.isEnabled = edit.isEnabled
        firmware.isEnabled = enabled && session == null
        rootRead.isEnabled = enabled && session == null && workFile != null
        showBondedSelection()
        val blocked = connectionBlockReason()
        connectionHint.text = when {
            session?.state?.value == XiaomiClassicSession.State.READY -> "Classic 认证就绪；可单独确认安装。BLE 诊断成功不代表认证成功。"
            session != null -> "Classic 会话进行中；只有 READY 才可安装。"
            blocked != null -> "$blocked。操作顺序：本页 Root 读取 → 点击候选记录 → 系统配对 → 连接并认证。BLE MTU / FE95 仅为诊断，不能解锁安装。"
            else -> "已选择 AuthKey 与设备；点击连接并认证，不会自动安装。"
        }
        connect.isEnabled = BandConnectionGate.canConnect(enabled, session != null, blocked)
        install.isEnabled = BandConnectionGate.canInstall(enabled, workFile != null, session?.state?.value?.name)
        for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = enabled && session == null
        bleScan.isEnabled = enabled && session == null
        for (i in 0 until bleDevices.childCount) bleDevices.getChildAt(i).isEnabled = enabled && session == null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun showBondedSelection() {
        val mac = records.getOrNull(selected ?: -1)?.mac ?: return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bondedInfo.text = "所选配对设备：尚无读取权限；未请求权限或发现。"
            deviceDiagnostic = "selected-bonded-device=permission-unavailable"
            return
        }
        try {
            val device = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(mac, true) }
            if (device == null) {
                bondedInfo.text = "所选 MAC 不在系统配对列表；无 SPP 服务线索。未扫描 / 发现。"
                deviceDiagnostic = "selected-bonded-device=absent; SPP=no-cached-evidence"
                return
            }
            val type = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                else -> "UNKNOWN"
            }
            // BluetoothDevice.getUuids returns the local cache; never call fetchUuidsWithSdp.
            val uuids = device.uuids?.map { it.uuid }?.take(32).orEmpty()
            val spp = ClassicProfile.SERVICE_UUID in uuids
            bondedInfo.text = "系统配对列表所选设备：${ClassicRedaction.text(device.name ?: "未命名")}\n" +
                "type=$type · BONDED\n已缓存服务 UUID（非本次发现 / 广播实测）：${uuids.joinToString().ifEmpty { "未知 / 空" }}\n" +
                if (spp) "有标准 SPP 服务缓存线索，不保证当前可连接。" else "无 SPP 服务线索；缓存可能不完整，不能据此断言设备不支持。未发起发现。"
            deviceDiagnostic = "selected-bonded-device=BONDED; type=$type; cached-SPP=$spp; uuid-count=${uuids.size}"
        } catch (_: SecurityException) {
            bondedInfo.text = "配对设备缓存读取权限不可用；未发起发现。"
            deviceDiagnostic = "selected-bonded-device=permission-unavailable"
        }
    }
    private fun connectionBlockReason(): String? {
        val record = records.getOrNull(selected ?: -1)
        val hasPermission = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this,
            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bonded = if (hasPermission && record != null && BandConnectionGate.validMac(record.mac)) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                adapter != null && adapter.isEnabled && adapter.bondedDevices.any {
                    it.address.equals(record.mac, true) && it.bondState == BluetoothDevice.BOND_BONDED
                }
            } catch (_: SecurityException) { null }
        } else null
        return BandConnectionGate.reason(records.isNotEmpty(), record?.mac, bonded)
    }
    override fun onResume() { super.onResume(); refresh() }

    /** Main-thread gate covers file work, Root, permission prompts, authentication and installation. */
    private fun launchOperation(block: suspend () -> Unit) {
        if (!foreground || busy || dialog != null) return
        busy = true; refresh()
        val request = epoch
        operation = lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (foreground && epoch == request) {
                    clearKeys()
                    session?.let { lastClassicPhase = it.phase; it.close() }; session = null
                    observer?.cancel(); observer = null
                    sessionState.text = "Classic：CLOSED（操作失败，连接已关闭）"
                }
                if (foreground && epoch == request) status.text = if (submitted && !confirmed)
                    "设备结果未知：安装请求可能已发送。请在手环确认状态；勿自动或盲目重刷。"
                else "操作被拒绝或失败；未确认安装。请检查私有文件、系统已配对状态、Root / 权限和目标型号。"
            } finally {
                if (epoch == request) { busy = false; operation = null; refresh() }
            }
        }
    }
    private fun editCopy() {
        val source = workFile ?: return
        if (busy || !foreground) return
        val id = newId.text.toString()
        showDialog("仅修改副本 ID？", "保持原 ID 字节长度，不修复签名 / 校验，不保证手环接受。原文件不覆盖。", "生成副本") {
            launchOperation {
                val result = try {
                    withContext(Dispatchers.IO) { WatchfaceIdEditor.createCopy(filesDir, source, id) }
                } catch (e: IllegalArgumentException) {
                    status.text = "ID 修改被拒绝：${e.message}"
                    return@launchOperation
                }
                workFile = result.file
                renderSummary()
                status.text = "ID 修改成功：${result.oldId} → ${result.newId}\n尚未安装；可返回导出。"
            }
        }
    }
    private fun returnCopy() = launchOperation {
        val file = workFile ?: error("无副本")
        val info = withContext(Dispatchers.IO) { InstallConsent.capture(filesDir, file) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        setResult(RESULT_OK, Intent().apply {
            data = uri; clipData = ClipData.newRawUri("watchface", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(EXTRA_PRIVATE_PATH, file.canonicalPath)
            putExtra(EXTRA_RESULT_ID, info.id); putExtra(EXTRA_RESULT_SHA256, info.sha256)
            // Returning a local copy never claims hardware installation (even after a prior install).
            putExtra(EXTRA_RESULT_INSTALLED, false)
        })
        finish()
    }
    private fun showDialog(title: String, message: String, positive: String, action: () -> Unit) {
        if (!foreground || dialog != null) return
        val d = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton(positive) { _, _ ->
                dialog = null; refresh()
                if (foreground) action()
            }.create()
        dialog = d
        d.setOnDismissListener { if (dialog === d) { dialog = null; refresh() } }
        d.show(); refresh()
    }
    private fun clearKeys() {
        synchronized(secretLock) { records.forEach { it.close() }; records = emptyList() }
        selected = null; choices.removeAllViews()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun readKeys() {
        if (!foreground || busy || dialog != null || session != null) return
        clearKeys(); busy = true; refresh()
        status.text = "正在 Root 只读获取 n67cn 密钥；不会自动连接。"
        val request = epoch
        rootTask = executor.submit {
            try {
                val found = RootAuthKeySource(applicationContext).read()
                synchronized(secretLock) {
                    if (!foreground || epoch != request) { found.forEach { it.close() }; return@submit }
                    records = found
                }
                runOnUiThread {
                    if (!foreground || epoch != request) return@runOnUiThread
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    records.forEachIndexed { index, record ->
                        choices.addView(Button(this).apply {
                            isSaveEnabled = false
                            text = "选择记录 ${index + 1} · ${record.model}\nMAC：${record.mac}\nAuthKey：••••••••••••••••••••••••••••••••"
                            setOnClickListener {
                                if (!busy && this@BandInstallActivity.foreground && session == null) {
                                    selected = index
                                    status.text = "已选择记录 ${index + 1} · MAC ${record.mac}；密钥仅在内存。尚未连接。"
                                    refresh()
                                }
                            }
                        })
                    }
                    busy = false; rootTask = null
                    status.text = "已读取 ${records.size} 条 n67cn 候选。请显式选择设备；尚未连接、未安装。"
                    refresh()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (foreground && epoch == request) {
                        clearKeys(); busy = false; rootTask = null
                        status.text = "安全读取失败：Root、快照或设备记录未通过校验；未使用密钥。如持续失败请清除本应用缓存。"
                        refresh()
                    }
                }
            }
        }
    }
    private fun classicPermissionsGranted() = ClassicPermissions.granted(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun authenticate() {
        if (!foreground || busy || dialog != null || session != null) return
        val blocked = connectionBlockReason()
        if (blocked != null) { status.text = blocked; refresh(); return }
        if (!classicPermissionsGranted()) {
            pendingPermission = true; busy = true; refresh()
            permission.launch(ClassicPermissions.required(Build.VERSION.SDK_INT).toTypedArray())
            return
        }
        launchOperation {
            val record = records.getOrNull(selected ?: -1)
            if (record == null || !BandConnectionGate.validMac(record.mac)) {
                status.text = "请先读取并选择AuthKey；请选设备并检查 MAC 格式。"
                refresh(); return@launchOperation
            }
            // Recheck inside the async block too: permissions can be revoked after the click.
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                status.text = "蓝牙连接权限已撤销；未连接。"
                return@launchOperation
            }
            // Only query already-bonded devices. Transport stops existing local discovery; never starts it.
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled || adapter.bondedDevices.none {
                    it.address.equals(record.mac, true) && it.bondState == BluetoothDevice.BOND_BONDED
                }) {
                clearKeys()
                status.text = "需系统配对：请先手动开启蓝牙并在系统设置配对目标 MAC，再返回重新读取并选择AuthKey。不会自动配对。"
                return@launchOperation
            }
            submitted = false; confirmed = false
            attemptedFirmware = ClassicFirmware.diagnostic(firmware.text.toString())
            val link = XiaomiClassicSession(AndroidClassicTransport(this), Build.VERSION.SDK_INT, Build.MODEL, "CN")
            session = link
            lastDiagnostic = "连接尝试进行中"
            observer = lifecycleScope.launch {
                link.state.collect { state ->
                    sessionState.text = "Classic：$state · ${link.phase}"
                    refresh()
                }
            }
            val mac = record.mac
            val key = record.copyKeyBytes()
            clearKeys()
            try {
                link.connectAndAuthenticate(mac, key)
                lastDiagnostic = "phase=${link.phase}\n${link.transportSnapshot.diagnostic()}"
                status.text = "READY：Classic 已连接并通过认证。尚未安装；只有点击安装并再次确认才发送文件。"
            } catch (e: Exception) {
                val report = rememberFailure(link, e)
                link.close()
                if (session === link) {
                    clearKeys()
                    observer?.cancel(); observer = null; session = null
                    if (e is CancellationException && e !is TimeoutCancellationException) {
                        sessionState.text = "Classic：CLOSED · ${report.phase}（用户取消或页面离开）"
                    } else {
                        val reason = BandConnectionGate.failure(report)
                        sessionState.text = "Classic：FAILED → CLOSED · ${report.phase}"
                        val attempt = if (report.phase in listOf(ClassicPhase.BONDED_CHECK, ClassicPhase.DISCOVERY_CANCEL))
                            "连接前置检查失败，未进入 socket.connect()"
                        else "已实际尝试连接"
                        status.text = "连接 / 认证失败：$reason\n$attempt；密钥随后按安全策略清空（按钮灰色不是此前未选 key）。再次尝试需重新读取；未安装。"
                    }
                }
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            } finally { key.fill(0) }
        }
    }
    private fun confirmInstall() = launchOperation {
        val link = session ?: error("尚未认证")
        check(link.state.value == XiaomiClassicSession.State.READY)
        val file = workFile ?: error("无副本")
        // Capture immutable consent fields when opening the confirmation dialog, not on first page load.
        val consent = withContext(Dispatchers.IO) { InstallConsent.capture(filesDir, file) }
        showDialog("确认发送实验性表盘？", "文件：${consent.name}\nID：${consent.id}\n大小：${consent.size} 字节\nSHA-256：${consent.sha256}\n\n目标：小米手环 9 Pro / n67cn。协议尚未真机验证；BIN 头和 ID 不保证手环机型兼容。修改 ID 不修复签名，可能失败或使设备异常。\n认证成功、上传 100% 均不等于安装成功。上传后异常 / 取消则结果未知，勿盲目重刷。", "确认风险并发送") {
            launchOperation {
                check(session === link && link.state.value == XiaomiClassicSession.State.READY)
                // No other asynchronous UI action can mutate this private file while consent is outstanding.
                withContext(Dispatchers.IO) { consent.verify(filesDir, file) }
                submitted = true; confirmed = false
                try {
                    val request = epoch
                    val result = link.install(file) { p ->
                        runOnUiThread {
                            if (foreground && epoch == request && !confirmed)
                                status.text = "传输 / 校验进度：${p.completed}/${p.total}\n进度不是成功，等待安装结果及设备激活确认。"
                        }
                    }
                    confirmed = result.activeConfirmed
                    status.text = if (confirmed) "安装成功：设备已确认 ID ${result.id} 已安装并激活。"
                    else "设备未确认激活，结果未知；勿盲目重刷。"
                } catch (e: Exception) {
                    val report = rememberFailure(link, e)
                    status.text = "设备结果未知；勿盲目重刷。\n${BandConnectionGate.failure(report)}"
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                } finally {
                    lastClassicPhase = link.phase
                    link.close()
                    if (session === link) {
                        observer?.cancel(); session = null
                        sessionState.text = "Classic：CLOSED · $lastClassicPhase"
                    }
                }
            }
        }
    }
    private fun scanDiagnostic() {
        if (!foreground || busy || dialog != null || session != null) return
        if (!BlePermissions.granted(this)) {
            pendingBlePermission = true; busy = true; refresh()
            blePermission.launch(BlePermissions.required()); return
        }
        launchOperation {
            clearKeys(); bleDevices.removeAllViews()
            status.text = "独立 BLE 扫描中：不认证，不上传。"
            val seen = mutableSetOf<String>()
            withTimeoutOrNull(15_000) {
                scanXiaomiDevices(this@BandInstallActivity).collect { candidate ->
                    if (seen.add(candidate.address)) bleDevices.addView(Button(this@BandInstallActivity).apply {
                        text = "${candidate.name} · ${candidate.address} · ${candidate.rssi} dBm"
                        isEnabled = false
                        setOnClickListener { diagnose(candidate) }
                    })
                }
            }
            status.text = "独立 BLE 扫描结束：${seen.size} 个候选。未认证、未安装；与 Classic 安装流程独立。"
        }
    }
    private fun diagnose(candidate: BleCandidate) {
        if (!foreground || busy || dialog != null || session != null) return
        if (!XiaomiBleProfile.BAND9PRO_NAME.matches(candidate.name)) {
            status.text = "广播名不匹配，拒绝 BLE 诊断连接；FE95 是共享服务。"; return
        }
        showDialog("仅连接 BLE 诊断？", "${candidate.name}\n${candidate.address}\n只发现服务与协商 MTU，不认证、不写入表盘。广播名可伪造。", "连接诊断") {
            launchOperation {
                clearKeys()
                val link = AndroidBleTransport(this@BandInstallActivity); bleLink = link
                try {
                    link.connect(candidate.device)
                    val services = link.discoveredServices()
                    status.text = "BLE 诊断完成：MTU ${link.mtu}，FE95=${XiaomiBleProfile.SERVICE in services}。已断开；这不是 Classic 认证，也不是安装成功。"
                } finally { link.close(); if (bleLink === link) bleLink = null }
            }
        }
    }
    private fun stopMessage() = if (submitted && !confirmed)
        "已取消并断开、清空密钥。安装请求可能已发送，设备结果未知；请在手环确认，勿自动或盲目重刷。"
    else if (confirmed) "已断开并清空密钥。此前设备已确认安装并激活。"
    else "已取消并断开、清空密钥。未确认安装；返回后需重新读取和认证。"
    private fun stopFlow() {
        synchronized(secretLock) { ++epoch }
        pendingPermission = false; pendingBlePermission = false
        bleLink?.close(); bleLink = null
        dialog?.dismiss(); dialog = null
        operation?.cancel(); operation = null
        observer?.cancel(); observer = null
        session?.let { lastClassicPhase = it.phase; it.close() }; session = null
        rootTask?.cancel(true); rootTask = null
        clearKeys(); busy = false
        sessionState.text = "Classic：CLOSED · $lastClassicPhase（用户取消或页面离开；密钥已清空）"
        refresh()
    }
    override fun onStop() {
        synchronized(secretLock) { foreground = false }
        stopFlow(); status.text = stopMessage()
        if (workFile == null) initialized = false
        super.onStop()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        workFile?.let { outState.putString(STATE_WORK_PATH, it.absolutePath) }
        outState.putBoolean(STATE_SUBMITTED, submitted)
        outState.putBoolean(STATE_CONFIRMED, confirmed)
        super.onSaveInstanceState(outState)
    }
    private suspend fun renderSummary() {
        val file = workFile ?: return
        val info = withContext(Dispatchers.IO) { InstallConsent.capture(filesDir, file) }
        summary.text = "私有工作副本：${info.name}\n当前 BIN ID：${info.id}\n大小：${info.size} 字节\nSHA-256：${info.sha256}\n型号兼容性 / 签名：未验证"
    }
    private fun privateSnapshot(path: String): File {
        val input = File(path).toPath()
        val root = listOf(filesDir, cacheDir).firstOrNull {
            input.startsWith(it.absoluteFile.toPath()) || input.startsWith(it.canonicalFile.toPath())
        } ?: throw IllegalArgumentException("拒绝非本 App 私有文件")
        val canonical = WorkingCopyResult.privateFile(root, path)
        val watchfaces = File(filesDir, "watchfaces")
        require(!Files.isSymbolicLink(watchfaces.toPath()))
        val directory = File(watchfaces, "band-install").apply { mkdirs() }
        require(directory.canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath()) && !Files.isSymbolicLink(directory.toPath()))
        val result = File.createTempFile("source-", ".bin", directory)
        try {
            canonical.inputStream().use { source -> result.outputStream().use { out ->
                val b = ByteArray(65536); var size = 0L
                while (true) { val n = source.read(b); if (n < 0) break; size += n; require(size <= WatchfaceIdEditor.MAX_BYTES); out.write(b, 0, n) }
            } }
            result.inputStream().use { inputStream ->
                val header = ByteArray(0xa8); var count = 0
                while (count < header.size) { val n = inputStream.read(header, count, header.size - count); require(n > 0); count += n }
                WatchfaceIdEditor.validatedId(header)
            }
            return WorkingCopyResult.privateFile(filesDir, result.path)
        } catch (e: Throwable) { result.delete(); throw e }
    }
}
