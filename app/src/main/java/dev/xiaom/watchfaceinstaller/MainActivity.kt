package dev.xiaom.watchfaceinstaller

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val rootSource = RootCacheSource()
    private var useRoot by mutableStateOf(true)
    private var rootAuthorized by mutableStateOf(false)
    private var rootStatus by mutableStateOf("Root 优先：请在 Root 管理器授权本应用（无需 Shizuku）")
    private var reader: ICacheReader? = null
    private var connection: ServiceConnection? = null
    private var serviceBinder: IBinder? = null
    private var serviceDeath: IBinder.DeathRecipient? = null
    private var bindingTimeout: Job? = null
    private var closed = false
    private var status by mutableStateOf("Shizuku 未连接")
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var items by mutableStateOf(emptyList<WatchfaceItem>())
    private var selected by mutableStateOf<WatchfaceInfo?>(null)
    private var pendingExport: File? = null
    private val directory get() = File(filesDir, "watchfaces")
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, CacheReaderService::class.java))
            .daemon(false).processNameSuffix("reader").version(3)
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        runOnUiThread {
            if (!closed && !useRoot && code == 100) {
                if (result == PackageManager.PERMISSION_GRANTED) bindReader()
                else { status = "Shizuku 授权被拒绝"; error = status }
            }
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            if (!closed) {
                status = "Shizuku 可用，等待授权 / 连接"
                try {
                    if (!useRoot && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindReader()
                } catch (e: Exception) { error = e.message ?: "检查权限失败" }
            }
        }
    }
    private val dead = Shizuku.OnBinderDeadListener {
        runOnUiThread { if (!closed && !useRoot) loseConnection("Shizuku 已停止，请重新连接") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { path ->
            File(path).takeIf { it.parentFile == directory && it.isFile }
        }
        setContent {
            MaterialTheme {
                val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) importDocument(uri)
                }
                val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    val file = pendingExport
                    pendingExport = null
                    if (uri != null && file != null) exportDocument(file, uri)
                }
                WatchfaceScreen(if (useRoot) rootStatus else status,
                    if (useRoot) rootAuthorized else connected, busy, error, notice, items, selected,
                    useRoot, { root ->
                        if (useRoot != root) {
                            releaseReader()
                            useRoot = root
                            items = emptyList(); error = null; notice = null
                        }
                    }, ::requestAccess, ::scan, ::selectCache,
                    { importer.launch(arrayOf("*/*")) },
                    {
                        selected?.let {
                            pendingExport = it.file
                            exporter.launch("watchface.bin")
                        }
                    }, { install(false) }, { install(true) })
            }
        }
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addBinderReceivedListenerSticky(received)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let { outState.putString("pendingExport", it.path) }
        super.onSaveInstanceState(outState)
    }

    private fun requestAccess() {
        if (useRoot) {
            operation {
                rootAuthorized = false
                rootStatus = "等待 su 授权（20 秒超时）；请在 Root 管理器允许本应用"
                try {
                    rootStatus = runInterruptible(Dispatchers.IO) { rootSource.authorize() }
                    rootAuthorized = true
                } catch (e: Exception) {
                    rootStatus = "Root 检查失败：${e.message}；不会自动修改 Root 策略"
                    throw e
                }
            }
            return
        }
        try {
            error = null
            if (!Shizuku.pingBinder()) { status = "Shizuku 未运行"; error = status; return }
            if (!useRoot && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindReader()
            else { status = "等待 Shizuku 授权"; Shizuku.requestPermission(100) }
        } catch (e: Exception) { error = e.message ?: "Shizuku 请求失败" }
    }

    private fun bindReader() {
        if (closed || useRoot || connection != null) return
        status = "正在绑定缓存读取服务…"
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closed || connection !== this) return
                try {
                    val death = IBinder.DeathRecipient {
                        runOnUiThread {
                            if (!closed && serviceBinder === binder) loseConnection("缓存读取服务已终止，请重新连接")
                        }
                    }
                    binder.linkToDeath(death, 0)
                    serviceBinder = binder
                    serviceDeath = death
                    reader = ICacheReader.Stub.asInterface(binder)
                    connected = true
                    status = "Shizuku 缓存读取服务已连接"
                    bindingTimeout?.cancel()
                } catch (e: Exception) { loseConnection("绑定失败：${e.message}") }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (connection === this) loseConnection("缓存读取服务已断开") }
            override fun onBindingDied(name: ComponentName) { if (connection === this) loseConnection("缓存读取服务绑定已失效") }
            override fun onNullBinding(name: ComponentName) { if (connection === this) loseConnection("缓存读取服务返回空绑定") }
        }
        connection = c
        try {
            Shizuku.bindUserService(args, c)
            bindingTimeout = lifecycleScope.launch {
                delay(15000)
                if (!connected && connection === c) loseConnection("绑定超时，请确认 Shizuku 正常运行后重试")
            }
        } catch (e: Exception) { loseConnection("绑定失败：${e.message}") }
    }

    private fun releaseReader() {
        bindingTimeout?.cancel()
        val binder = serviceBinder
        val death = serviceDeath
        serviceBinder = null; serviceDeath = null; reader = null; connected = false
        if (binder != null && death != null) {
            try { binder.unlinkToDeath(death, 0) }
            catch (e: Exception) { if (!closed) error = "清理死亡监听失败：${e.message}" }
        }
        val old = connection
        connection = null
        if (old != null) {
            try { Shizuku.unbindUserService(args, old, true) }
            catch (e: Exception) { if (!closed) error = "解除服务绑定失败：${e.message}" }
        }
    }

    private fun loseConnection(message: String) {
        releaseReader()
        items = emptyList()
        status = message
        error = message
    }

    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { busy = false }
        }
    }

    private fun scan() = operation {
        items = emptyList()
        if (useRoot) {
            check(rootAuthorized) { "请先授权 / 检查 Root" }
            val (result, enriched) = runInterruptible(Dispatchers.IO) {
                val result = rootSource.scan()
                result to result.files.map {
                    val metadata = rootSource.metadata(it.path)
                    WatchfaceItem(it.name, it.path, it.size, it.modified, metadata.metadata, metadata.warnings)
                }
            }
            items = enriched
            error = result.diagnostics.joinToString("\n").takeIf { it.isNotEmpty() }
            notice = "Root 找到 ${items.size} 个 BIN 文件" +
                if (result.truncated) "；达到大小 / 深度 / 数量限制，结果不完整" else "；扫描完成"
            return@operation
        }
        val service = reader ?: throw IOException("请先连接 Shizuku")
        val result = withContext(Dispatchers.IO) {
            val json = JSONObject(service.scan())
            val files = json.getJSONArray("files")
            val found = (0 until files.length()).map { i ->
                val o = files.getJSONObject(i)
                val path = o.getString("path")
                val metadata = WatchfaceXml.load(path) { service.readMetadata(path, it) }
                WatchfaceItem(o.getString("name"), path, o.getLong("size"), o.getLong("modified"), metadata.metadata, metadata.warnings)
            }
            val errors = json.getJSONArray("errors")
            Triple(found, (0 until errors.length()).joinToString("\n") { errors.getString(it) }, json.getBoolean("truncated"))
        }
        if (reader !== service) throw IOException("扫描期间服务已断开，请重试")
        items = result.first
        error = result.second.takeIf { it.isNotEmpty() }
        notice = "找到 ${items.size} 个 BIN 文件" + if (result.third) "；扫描达到深度 / 数量限制，结果不完整" else ""
    }

    private suspend fun copyAndInspect(open: () -> java.io.InputStream): WatchfaceInfo = withContext(Dispatchers.IO) {
        val file = open().use { WatchfaceFiles.copyInto(directory, it) }
        try { WatchfaceParser.inspect(file) }
        catch (e: Exception) { file.delete(); throw e }
    }

    private fun selectCache(item: WatchfaceItem) = operation {
        selected = if (useRoot) {
            check(rootAuthorized) { "请先授权 / 检查 Root" }
            // Keep ownership outside runInterruptible: prompt coroutine cancellation
            // can discard a successfully returned value while switching to Main.
            var copied: File? = null
            try {
                runInterruptible(Dispatchers.IO) {
                    val file = rootSource.copyInto(directory, item.path)
                    copied = file
                    if (Thread.currentThread().isInterrupted) throw IOException("读取已取消")
                    WatchfaceParser.inspect(file)
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { copied?.delete() }
                throw e
            }
        } else {
            val service = reader ?: throw IOException("请先连接 Shizuku")
            copyAndInspect { ParcelFileDescriptor.AutoCloseInputStream(service.openReadOnly(item.path)) }
        }
        // Re-read only the fixed XML siblings, now using the actual private BIN ID.
        // A path ID was sufficient for the list, never for selected-file details.
        selected?.let { info ->
            val metadata = if (useRoot) {
                runInterruptible(Dispatchers.IO) { rootSource.metadata(item.path, info.id, "BIN") }
            } else {
                val service = reader ?: throw IOException("请先连接 Shizuku")
                withContext(Dispatchers.IO) {
                    WatchfaceXml.load(item.path, info.id, "BIN") { service.readMetadata(item.path, it) }
                }
            }
            val verified = WatchfaceXml.verify(metadata.metadata, info.id)
            selected = info.copy(metadata = verified.metadata, metadataWarnings = metadata.warnings + verified.warnings)
        }
        notice = "已创建应用私有副本；不会修改缓存源文件"
    }

    private fun importDocument(uri: Uri) = operation {
        selected = copyAndInspect { contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文档") }
        notice = "已导入私有副本（最多 32 MiB）；格式识别不代表设备兼容性验证"
    }

    private fun exportDocument(file: File, uri: Uri) = operation {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("无法写入目标文档")
        }
        notice = "已导出到所选文档"
    }

    private fun install(share: Boolean) {
        try {
            error = null
            val info = selected ?: throw IOException("请先选择或导入 BIN")
            require(info.format == "XIAOMI_BIN") { "仅识别出完整 Xiaomi 头部的文件可交给安装工具" }
            if (share) InstallerBridge.shareWithCompatibleInstaller(this, info.file)
            else InstallerBridge.openWithCompatibleInstaller(this, info.file)
            notice = "已请求外部安装工具选择器；尚未确认安装，兼容性由外部工具检查"
        } catch (e: Exception) { error = e.message ?: "无法打开安装工具" }
    }

    override fun onDestroy() {
        closed = true
        Shizuku.removeRequestPermissionResultListener(permission)
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        releaseReader()
        super.onDestroy()
    }
}

@Composable
private fun WatchfaceScreen(
    status: String, connected: Boolean, busy: Boolean, error: String?, notice: String?,
    files: List<WatchfaceItem>, selected: WatchfaceInfo?, useRoot: Boolean, mode: (Boolean) -> Unit,
    request: () -> Unit, scan: () -> Unit,
    select: (WatchfaceItem) -> Unit, onImport: () -> Unit, export: () -> Unit, install: () -> Unit, share: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(BinFilter.CANDIDATE) }
    var keyword by rememberSaveable { mutableStateOf("") }
    val visibleFiles = remember(files, filter, keyword) { WatchfaceCatalog.filter(files, filter, keyword) }
    val candidateCount = remember(files) { files.count(WatchfaceCatalog::isCandidate) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Xiaomi Watchface Installer", style = MaterialTheme.typography.headlineSmall)
            Text("小米手环 9 Pro · 只读缓存提取 / 外部工具交接")
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mode(true) }, enabled = !busy && !useRoot) { Text("Root（默认）") }
                OutlinedButton(onClick = { mode(false) }, enabled = !busy && useRoot) { Text("Shizuku（可选）") }
            }
            Text(if (useRoot) "本应用通过 su 申请 Root；仅扫描两个官方外部缓存目录。拒绝、缺失、超限会分别报告。" else "Shizuku 需另行运行 / 授权；ADB UID 2000 不是 Root，可能无法读取 Android/data。")
            Button(onClick = request, enabled = !busy) {
                Text(if (useRoot) "授权 / 检查 Root" else "授权 / 连接 Shizuku")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = scan, enabled = !busy && connected) { Text("扫描缓存") }
                Button(onClick = onImport, enabled = !busy) { Text("导入 BIN") }
            }
            Text("候选仅按路径筛选：文件名为 resource.bin（忽略大小写），且祖先目录无 resources 或 _lua。仅候选读取固定 description.xml / resources/manifest.xml；XML 名称需匹配缓存路径 ID，选择后再核对 BIN ID。候选不等于有效性验证。")
            Text("资源 BIN 包含所有非候选项（也可能是其他未分类 BIN），并非内容鉴定。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BinFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        label = { Text(option.label) })
                }
            }
            OutlinedTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("XML 名称、文件名或路径关键词") })
            Text("显示 ${visibleFiles.size} / ${files.size} 个 BIN；候选 $candidateCount，资源/其他 ${files.size - candidateCount}（数量仅限本次扫描结果）")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
        }
        selected?.let { info ->
            item {
                Text("私有副本：${info.file.name}\n格式：${info.format}\nMagic：${info.magic}\nID：${info.id ?: "未识别"}\nBIN 解析名：${info.name ?: "未识别"}\nSHA-256：${info.sha256}")
                info.metadata?.let { meta ->
                    Text("XML 名称：${meta.name}\n来源：${meta.source}（已核对 BIN ID）\nXML ID：${meta.id}\n${meta.declaration}")
                }
                info.metadataWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("头部识别不是完整有效性、签名或设备兼容性验证。")
                Button(onClick = export, enabled = !busy) { Text("导出副本") }
                Button(onClick = install, enabled = !busy && info.format == "XIAOMI_BIN") { Text("用安装工具打开") }
                Button(onClick = share, enabled = !busy && info.format == "XIAOMI_BIN") { Text("分享文件到安装工具") }
                Text("接收方需支持文件 URI；只打开首页或要求重选文件表示该入口未完成直接导入，可尝试另一方式或手动导出导入。")
            }
        }
        items(visibleFiles, key = { it.path }) { item ->
            val modifiedText = if (item.modified > 0) {
                dateFormat.format(Date(item.modified))
            } else "未知"
            Text("${item.name}\n${if (WatchfaceCatalog.isCandidate(item)) "候选（未验证）" else "资源/其他 BIN"}\n大小：${item.size} bytes\n修改时间：$modifiedText\n${item.path}")
            item.metadata?.let { meta ->
                Text("XML 名称：${meta.name}\n来源：${meta.source}\nXML ID：${meta.id}（仅匹配路径，待核对 BIN）\n${meta.declaration}")
            }
            item.metadataWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { select(item) }, enabled = !busy && connected) { Text("选择并检查") }
        }
    }
}
