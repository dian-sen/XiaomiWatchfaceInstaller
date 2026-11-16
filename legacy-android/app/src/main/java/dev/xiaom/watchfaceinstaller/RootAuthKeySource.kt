package dev.xiaom.watchfaceinstaller

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent of cache/BLE sources. Only called after explicit foreground user action. */
class RootAuthKeySource(private val context: Context) {
    private class Snapshot(val dir: File, val sizes: List<Long>, val hashes: List<List<Byte>>)
    fun read(): List<AuthKeyRecord> {
        val records = mutableListOf<AuthKeyRecord>()
        var root: File? = null
        try {
            check(!Thread.currentThread().isInterrupted)
            root = Files.createTempDirectory(context.cacheDir.toPath(), "auth-private-").toFile()
            Os.chmod(root.path, 448) // 0700; never shared/external storage
            val ns = if (probe(false)) false else if (probe(true)) true else throw IOException()
            repeat(2) { attempt ->
                check(!Thread.currentThread().isInterrupted)
                val first = snapshot(root, "a$attempt", ns)
                val second = snapshot(root, "b$attempt", ns)
                if (first.sizes == second.sizes && first.hashes == second.hashes) {
                    inspect(File(second.dir, "device_db"), records)
                    check(!Thread.currentThread().isInterrupted)
                    return records
                }
                check(first.dir.deleteRecursively() && second.dir.deleteRecursively())
            }
            throw IOException()
        } catch (_: Exception) {
            records.forEach { it.close() }
            throw IOException("安全读取失败：Root、数据库快照稳定性或设备记录未通过校验；未使用密钥")
        } finally {
            // Includes SQLite-generated shm/journal files. A deletion failure must not report success.
            if (root != null && !root.deleteRecursively()) {
                records.forEach { it.close() }
                throw IOException("私有临时快照清理失败；未使用密钥，请清除本应用缓存")
            }
        }
    }

    private fun probe(namespace: Boolean): Boolean = try {
        rootProcess(AuthKeyCommands.probe(namespace)) { input ->
            check(line(input) == "AUTH_ROOT_OK")
        }; true
    } catch (_: Exception) { false }

    private fun snapshot(parent: File, name: String, namespace: Boolean): Snapshot {
        val dir = File(parent, name)
        check(dir.mkdir()); Os.chmod(dir.path, 448)
        return rootProcess(AuthKeyCommands.snapshot(namespace)) { input ->
            val n = line(input).toLong(); val m = line(input).toLong(); val present = line(input).toLong()
            check(n in 1..AuthKeyCommands.BASE_LIMIT && m in 0..AuthKeyCommands.WAL_LIMIT)
            check(present in 0..1 && (present == 1L || m == 0L))
            val hashes = mutableListOf<List<Byte>>()
            hashes += copy(input, File(dir, "device_db"), n)
            if (present == 1L) hashes += copy(input, File(dir, "device_db-wal"), m)
            Snapshot(dir, listOf(n, m, present), hashes)
        }
    }

    private fun copy(input: InputStream, destination: File, size: Long): List<Byte> {
        check(destination.createNewFile()); Os.chmod(destination.path, 384) // 0600
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        try {
            FileOutputStream(destination).use { out ->
                var left = size
                while (left > 0) {
                    check(!Thread.currentThread().isInterrupted)
                    val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    check(count > 0)
                    digest.update(buffer, 0, count); out.write(buffer, 0, count); left -= count
                }
            }
            return digest.digest().toList()
        } finally { buffer.fill(0) }
    }

    private fun inspect(file: File, records: MutableList<AuthKeyRecord>) {
        // OPEN_READWRITE on the private copy only: SQLite reconstructs its own SHM and applies WAL.
        // Never copy live SHM, never open/checkpoint the original database.
        SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext())
            }
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(device)", null).use { c ->
                while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name"))
            }
            check(columns.containsAll(setOf("did", "productId", "model", "name", "extraValues", "identifier", "alias", "status", "detail", "uploaded", "createTime")))
            db.rawQuery("SELECT type FROM sqlite_master WHERE name='device'", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "table" && !c.moveToNext())
            }
            // Reject overflow rather than silently selecting the first of more than ten records.
            db.rawQuery("SELECT count(*), max(length(detail)) FROM device WHERE model=?",
                arrayOf(AuthKeyRecord.MODEL)).use { c ->
                check(c.moveToFirst() && c.getLong(0) in 1L..10L && c.getLong(1) in 1..65536)
            }
            db.rawQuery("SELECT model, detail FROM device WHERE model=? LIMIT 10",
                arrayOf(AuthKeyRecord.MODEL)).use { c ->
                while (c.moveToNext()) records += AuthKeyRecord.parse(c.getString(0), c.getString(1))
            }
            check(records.isNotEmpty())
        }
    }

    private fun line(input: InputStream): String {
        val result = StringBuilder()
        repeat(32) {
            val b = input.read(); check(b >= 0)
            if (b == 10) return result.toString()
            check(b in 32..126); result.append(b.toChar())
        }
        throw IOException()
    }

    private fun <T> rootProcess(command: String, consume: (InputStream) -> T): T {
        check(!Thread.currentThread().isInterrupted)
        // su is resolved by Android's fixed system PATH; scripts accept no caller-controlled inputs.
        val builder = ProcessBuilder("su", "-c", command).redirectError(File("/dev/null"))
        builder.environment()["PATH"] = "/system/bin:/system/xbin:/sbin:/debug_ramdisk"
        val process = builder.start()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "auth-timeout").apply { isDaemon = true }
        }
        val timeout = timer.schedule({ process.destroyForcibly() }, 20, TimeUnit.SECONDS)
        try {
            process.outputStream.close()
            return process.inputStream.use { input ->
                val result = consume(input)
                check(input.read() == -1 && process.waitFor() == 0)
                check(!Thread.currentThread().isInterrupted)
                result
            }
        } finally {
            timeout.cancel(false); timer.shutdownNow(); process.destroyForcibly()
        }
    }
}
