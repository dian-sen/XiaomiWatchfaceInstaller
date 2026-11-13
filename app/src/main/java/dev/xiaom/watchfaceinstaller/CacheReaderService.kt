package dev.xiaom.watchfaceinstaller

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CacheReaderService : ICacheReader.Stub() {
    private val roots = listOf(
        File("/storage/emulated/0/Android/data/com.mi.health/files/WatchFace"),
        File("/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local")
    )
    override fun scan(): String {
        val out = JSONArray(); var count=0
        fun walk(root: File, dir: File, depth: Int) {
            if (depth > 8 || count >= 200) return
            dir.listFiles()?.forEach { f ->
                if (count >= 200) return@forEach
                if (f.isDirectory) walk(root,f,depth+1)
                else if (f.isFile && f.extension.equals("bin",true) && f.canonicalPath.startsWith(root.canonicalPath + "/")) {
                    out.put(JSONObject().apply { put("name",f.name); put("path",f.canonicalPath); put("size",f.length()); put("modified",f.lastModified()) }); count++
                }
            }
        }
        roots.filter { it.isDirectory }.forEach { walk(it,it,0) }
        return JSONObject().apply { put("files",out); put("truncated",count>=200) }.toString()
    }
    override fun openReadOnly(path: String): ParcelFileDescriptor {
        val f=File(path).canonicalFile
        require(roots.any { val r=it.canonicalFile.path; f.path.startsWith(r+"/") } && f.isFile && f.length()<=32*1024*1024)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun destroy() { kotlin.system.exitProcess(0) }
}
