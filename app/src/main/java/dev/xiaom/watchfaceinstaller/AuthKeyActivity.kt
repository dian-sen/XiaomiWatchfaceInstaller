package dev.xiaom.watchfaceinstaller

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Explicit, warned clipboard export only. No saved state, Intent, preferences or logs for secrets. */
class AuthKeyActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardLease = SecretClipboardLease()
    private var plaintext by mutableStateOf<CharArray?>(null)
    private var confirmation: AlertDialog? = null
    private val conceal = Runnable { hidePlaintext() }
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private var operation: Future<*>? = null
    private var epoch = 0
    private var foreground = false
    private var records: List<AuthKeyRecord> = emptyList()
    private var choices by mutableStateOf(emptyList<String>())
    private var selected by mutableStateOf<Int?>(null)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("尚未读取；仅支持 miwear.watch.n67cn")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("设备认证密钥（Root）", style = MaterialTheme.typography.headlineSmall)
                        Text("点击后通过本应用 su 只读取得小米运动健康数据库的临时私有快照。只提取目标型号的 encrypt_key，不读取其他认证字段。不会修改 Root 策略或连接蓝牙。")
                        Text("密钥默认仅在本页内存以掩码显示，不保存、不传递给其他页面。可二次确认后短时显示并复制明文；离开即隐藏并清空，返回需要重新读取。")
                        Button(onClick = ::readKey, enabled = !busy) { Text(if (busy) "正在安全读取…" else "明确授权并读取 AuthKey") }
                        Text(status)
                    }
                    items(choices.size) { index ->
                        OutlinedButton(onClick = { choose(index) }, enabled = !busy) {
                            Text("${if (selected == index) "已选择 · " else "选择 · "}${choices[index]}")
                        }
                    }
                    if (selected != null) item {
                        Text(AuthKeyPrivacy.MASK)
                        Button(onClick = ::confirmPlaintext, enabled = !busy) { Text("显示并复制明文 AuthKey") }
                        plaintext?.let { Text("AuthKey：${String(it)}（最多 30 秒）") }
                    }
                    item { OutlinedButton(onClick = { finish() }) { Text("清空并返回") } }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); synchronized(lock) { foreground = true } }

    private fun clearLocked() {
        records.forEach { it.close() }; records = emptyList()
    }

    private fun hidePlaintext() {
        handler.removeCallbacks(conceal)
        plaintext?.fill('\u0000'); plaintext = null
        val cleared = clipboardLease.clearIfOwned(
            read = {
                val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                if (clip?.itemCount == 1) clip.getItemAt(0).text else null
            },
            clear = { getSystemService(ClipboardManager::class.java)?.let {
                if (Build.VERSION.SDK_INT >= 28) it.clearPrimaryClip()
                else it.setPrimaryClip(ClipData.newPlainText("", ""))
            } }
        )
        if (!cleared) status = "明文已隐藏；系统拒绝检查或清理剪贴板，请手动清除。"
    }

    private fun confirmPlaintext() {
        if (!foreground || busy || confirmation != null || selected == null) return
        val index = selected ?: return
        val d = AlertDialog.Builder(this).setTitle("确认显示并复制敏感 AuthKey？")
            .setMessage(AuthKeyPrivacy.WARNING).setNegativeButton("取消", null)
            .setPositiveButton("确认风险并复制") { _, _ ->
                if (foreground && selected == index) copyPlaintext(index)
            }.create()
        confirmation = d
        d.setOnDismissListener { if (confirmation === d) confirmation = null }
        d.show()
    }

    private fun copyPlaintext(index: Int) {
        hidePlaintext()
        try {
            val record = records.getOrNull(index) ?: error("记录已清空")
            record.withSecret { temporary ->
                val clipboard = getSystemService(ClipboardManager::class.java) ?: error("剪贴板不可用")
                val clip = ClipData.newPlainText("敏感 AuthKey", String(temporary))
                // Stable platform key, also understood by Android 12 clipboard UIs.
                if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipboardLease.remember(temporary)
                clipboard.setPrimaryClip(clip)
                plaintext = temporary.copyOf()
            }
            handler.postDelayed(conceal, AuthKeyPrivacy.TIMEOUT_MS)
            status = "已复制敏感 AuthKey；最多显示 30 秒。不得分享；离开本页即隐藏并尝试清理本次剪贴板。"
        } catch (_: Exception) {
            hidePlaintext()
            status = "复制失败：剪贴板不可用或系统拒绝。明文已隐藏；如系统已接收部分操作，请手动检查并清除剪贴板。"
        }
    }

    private fun readKey() {
        hidePlaintext()
        val request = synchronized(lock) {
            if (!foreground || busy) return
            clearLocked(); ++epoch
        }
        choices = emptyList(); selected = null; busy = true
        status = "等待 Root 授权及快照校验；不会读取后自动连接"
        operation = executor.submit {
            try {
                val found = RootAuthKeySource(applicationContext).read()
                synchronized(lock) {
                    if (!foreground || epoch != request) { found.forEach { it.close() }; return@submit }
                    records = found
                }
                runOnUiThread {
                    synchronized(lock) {
                        if (!foreground || epoch != request) return@runOnUiThread
                        choices = records.mapIndexed { index, record -> "记录 ${index + 1} · ${record.model} · ${record.mac}" }
                        busy = false
                        // Even a single record requires an explicit selection; never guess among duplicates.
                        status = "已安全读取 ${records.size} 条候选；请选择设备记录。未连接、未安装。"
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    synchronized(lock) {
                        if (!foreground || epoch != request) return@runOnUiThread
                        clearLocked(); busy = false
                        status = "安全读取失败：Root、快照或设备记录未通过校验。未使用密钥；如持续失败请清除本应用缓存。"
                    }
                }
            }
        }
    }

    private fun choose(index: Int) {
        synchronized(lock) {
            if (!foreground || index !in records.indices) return
            hidePlaintext()
            selected = index
            status = "已读取可用于后续认证，但未连接未安装。当前版本不会将密钥传给 BLE 页面。"
        }
    }

    override fun onStop() {
        confirmation?.dismiss(); confirmation = null
        hidePlaintext()
        synchronized(lock) { foreground = false; ++epoch; clearLocked() }
        operation?.cancel(true); operation = null
        choices = emptyList(); selected = null; busy = false
        status = "已清空内存中的密钥；需要重新点击读取"
        super.onStop()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
