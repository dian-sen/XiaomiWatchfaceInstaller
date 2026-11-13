package dev.xiaom.watchfaceinstaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var reader: ICacheReader? = null
    private var connection: android.content.ServiceConnection? = null
    private val args by lazy { Shizuku.UserServiceArgs(android.content.ComponentName(this, CacheReaderService::class.java)).daemon(false).processNameSuffix("reader").version(1) }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result -> if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) bindReader() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permission)
        setContent { WatchfaceScreen(::scan, ::requestAccess) }
    }
    private fun requestAccess() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) bindReader()
        else Shizuku.requestPermission(100)
    }
    private fun bindReader() {
        val c = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName, b: android.os.IBinder) { reader = ICacheReader.Stub.asInterface(b) }
            override fun onServiceDisconnected(n: android.content.ComponentName) { reader = null }
        }
        connection = c
        Shizuku.bindUserService(args, c)
    }
    private fun scan(): List<WatchfaceItem> = runCatching {
        val json = JSONArray(org.json.JSONObject(reader?.scan() ?: "{files:[]}").getString("files"))
        (0 until json.length()).map { val o=json.getJSONObject(it); WatchfaceItem(o.getString("name"),o.getString("path"),o.getLong("size"),o.getLong("modified")) }
    }.getOrDefault(emptyList())
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permission); connection?.let { Shizuku.unbindUserService(args,it,true) }; super.onDestroy() }
}

data class WatchfaceItem(val name:String,val path:String,val size:Long,val modified:Long)

@Composable
private fun WatchfaceScreen(scan: () -> List<WatchfaceItem>, request: () -> Unit) {
    var items by remember { mutableStateOf(emptyList<WatchfaceItem>()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Xiaomi Watchface Installer", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("优先支持：小米手环 9 Pro；提取使用 Shizuku。")
        Row { Button(onClick=request) { Text("授权 / 连接 Shizuku") }; Spacer(Modifier.width(8.dp)); Button(onClick={items=scan()}) { Text("扫描表盘缓存") } }
        Spacer(Modifier.height(8.dp))
        LazyColumn { items(items) { Text("${it.name}\n${it.size} bytes\n${it.path}", Modifier.padding(vertical=8.dp)) } }
    }
}
