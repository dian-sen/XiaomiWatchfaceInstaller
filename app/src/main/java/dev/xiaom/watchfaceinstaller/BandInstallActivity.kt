// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.xiaom.watchfaceinstaller.ble.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

/** Internal activity only: register android:exported="false", with NO intent filters. */
class BandInstallActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PRIVATE_PATH = "private_path"
        // RESULT_OK means a local copy was selected, NEVER that a device installed it.
        const val EXTRA_RESULT_ID = "result_id"
        const val EXTRA_RESULT_SHA256 = "result_sha256"
        const val EXTRA_RESULT_INSTALLED = "result_installed"
        private const val STATE_WORK_PATH = "work_path"
    }
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var devices: LinearLayout
    private var workFile: File? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var transport: AndroidBleTransport? = null
    private val candidates = linkedMapOf<String, BleCandidate>()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BlePermissions.granted(this)) scan() else status.text = "权限未获准；未扫描、未连接。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, 10, 0, 10); content.addView(this) }
        label("小米手环 9 Pro · 副本编辑与 BLE 诊断")
        label(XiaomiBleProfile.BLOCK_REASON)
        label("当前未实现已验证的 9 Pro 直装。可检查私有工作副本、等长修改 ID，以及自愿进行 BLE 服务诊断。不读取/索取 AuthKey，不自动切换 SPP，不写入认证或表盘数据。")
        summary = label("正在验证私有文件…")
        status = label("未扫描，未连接，未安装。")
        val newId = EditText(this).apply {
            hint = "新 ID（保持当前 9 或 12 位长度）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }
        content.addView(newId)
        content.addView(Button(this).apply {
            text = "生成改 ID 副本（不覆盖源文件）"
            setOnClickListener {
                val source = workFile ?: return@setOnClickListener
                val requested = newId.text.toString()
                AlertDialog.Builder(this@BandInstallActivity)
                    .setTitle("仅修改副本的 ID？")
                    .setMessage("保持原 ID 字节长度。不会修复签名/校验，也不保证手环接受。原文件不覆盖。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("生成副本") { _, _ -> lifecycleScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { WatchfaceIdEditor.createCopy(filesDir, source, requested) }
                            workFile = result.file; renderSummary()
                            status.text = "已生成新副本：${result.oldId} → ${result.newId}。尚未安装；签名和兼容性未验证。"
                        } catch (e: Exception) { if (e is CancellationException) throw e; status.text = "修改拒绝：${e.message}" }
                    } }.show()
            }
        })
        content.addView(Button(this).apply {
            text = "返回当前副本，交主界面导出"
            setOnClickListener {
                val file = workFile ?: return@setOnClickListener
                isEnabled = false
                lifecycleScope.launch {
                    try {
                        val info = withContext(Dispatchers.IO) { WatchfaceParser.inspect(file) }
                        val uri = FileProvider.getUriForFile(this@BandInstallActivity, "$packageName.files", file)
                        setResult(RESULT_OK, Intent().apply {
                            data = uri
                            clipData = ClipData.newRawUri("watchface", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra(EXTRA_PRIVATE_PATH, file.canonicalPath)
                            putExtra(EXTRA_RESULT_ID, info.id)
                            putExtra(EXTRA_RESULT_SHA256, info.sha256)
                            putExtra(EXTRA_RESULT_INSTALLED, false)
                        })
                        finish()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        status.text = "返回副本失败：${e.message}"
                    } finally { isEnabled = true }
                }
            }
        })
        content.addView(Button(this).apply {
            text = "查看安装确认与阻塞原因"
            setOnClickListener { lifecycleScope.launch {
                val file = workFile ?: return@launch
                try {
                    val info = withContext(Dispatchers.IO) { WatchfaceParser.inspect(file) }
                    AlertDialog.Builder(this@BandInstallActivity).setTitle("安装前检查（当前不可执行）")
                        .setMessage("文件：${file.name}\nSHA-256：${info.sha256}\nID：${info.id ?: "无法识别"}\n目标：Xiaomi Smart Band 9 Pro\n\n警告：BIN magic/ID 不证明型号兼容；修改 ID 不绕过签名。\n\n${XiaomiBleProfile.BLOCK_REASON}")
                        .setPositiveButton("关闭（不安装）", null).show()
                } catch (e: Exception) { if (e is CancellationException) throw e; status.text = "文件检查失败：${e.message}" }
            } }
        })
        content.addView(Button(this).apply {
            text = "扫描 BLE 设备（15 秒，仅诊断）"
            setOnClickListener { if (BlePermissions.granted(this@BandInstallActivity)) scan() else permissions.launch(BlePermissions.required()) }
        })
        content.addView(Button(this).apply { text = "取消 / 断开"; setOnClickListener { stop(); status.text = "已取消并关闭连接。未安装。" } })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(devices)
        lifecycleScope.launch {
            try {
                val path = savedInstanceState?.getString(STATE_WORK_PATH) ?: intent.getStringExtra(EXTRA_PRIVATE_PATH) ?: error("缺少私有工作副本路径")
                workFile = withContext(Dispatchers.IO) { privateSnapshot(path) }
                renderSummary()
            } catch (e: Exception) { if (e is CancellationException) throw e; summary.text = "拒绝文件：${e.message}" }
        }
    }
    private fun privateSnapshot(path: String): File {
        val input = File(path)
        require(input.isAbsolute && !Files.isSymbolicLink(input.toPath())) { "只接受绝对私有文件路径" }
        val canonical = input.canonicalFile
        require(listOf(filesDir, cacheDir).any { canonical.toPath().startsWith(it.canonicalFile.toPath()) }) { "拒绝非本 App 私有文件" }
        require(canonical.isFile && canonical.length() in 0xa8L..WatchfaceIdEditor.MAX_BYTES)
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
            return result
        } catch (e: Throwable) { result.delete(); throw e }
    }
    private suspend fun renderSummary() {
        val file = workFile ?: return
        val info = withContext(Dispatchers.IO) { WatchfaceParser.inspect(file) }
        summary.text = "私有工作副本：${file.name}\n格式：${info.format}\nID：${info.id}\n大小：${file.length()} 字节\nSHA-256：${info.sha256}\n型号兼容性/签名：未验证"
    }
    private fun scan() {
        stop(); candidates.clear(); devices.removeAllViews()
        scanJob = lifecycleScope.launch {
            status.text = "正在扫描，仅显示广播名匹配或带 FE95 服务的设备；广播名不等于身份认证。"
            try {
                withTimeoutOrNull(15_000) {
                    scanXiaomiDevices(this@BandInstallActivity).collect { candidate ->
                        if (candidates.put(candidate.address, candidate) == null) {
                            devices.addView(Button(this@BandInstallActivity).apply {
                                text = "${candidate.name}\n${candidate.address} · ${candidate.rssi} dBm"
                                setOnClickListener { choose(candidate) }
                            })
                        }
                    }
                }
                status.text = "扫描结束，${candidates.size} 个候选。未安装。9 Pro 也可能只通过经典蓝牙公布安装服务。"
            } catch (e: Exception) { if (e is CancellationException) throw e; status.text = "扫描失败：${e.message}" }
        }
    }
    private fun choose(candidate: BleCandidate) {
        scanJob?.cancel()
        if (!XiaomiBleProfile.BAND9PRO_NAME.matches(candidate.name)) {
            status.text = "型号广播名不匹配，拒绝连接。FE95 是共享服务，不证明是手环 9 Pro。"; return
        }
        AlertDialog.Builder(this).setTitle("仅连接诊断？")
            .setMessage("${candidate.name}\n${candidate.address}\n只发现服务并协商 MTU，不开启认证/上传，不修改表盘。广播名可伪造。")
            .setNegativeButton("取消", null).setPositiveButton("连接诊断") { _, _ ->
                connectionJob = lifecycleScope.launch {
                    val link = AndroidBleTransport(this@BandInstallActivity); transport = link
                    try {
                        status.text = "连接诊断中…"
                        link.connect(candidate.device)
                        val services = link.discoveredServices()
                        status.text = "服务发现完成，MTU ${link.mtu}，FE95=${XiaomiBleProfile.SERVICE in services}。已断开。\n${XiaomiBleProfile.BLOCK_REASON}"
                    } catch (e: Exception) { if (e is CancellationException) throw e; status.text = "诊断失败：${e.message}。未安装。" }
                    finally { link.close(); if (transport === link) transport = null }
                }
            }.show()
    }
    private fun stop() { scanJob?.cancel(); connectionJob?.cancel(); transport?.close(); transport = null }
    override fun onSaveInstanceState(outState: Bundle) {
        workFile?.let { outState.putString(STATE_WORK_PATH, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { stop(); super.onStop() }
    override fun onDestroy() { stop(); super.onDestroy() }
}
