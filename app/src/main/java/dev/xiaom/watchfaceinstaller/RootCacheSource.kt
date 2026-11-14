package dev.xiaom.watchfaceinstaller

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/** No Android dependency: all privileged access happens in this application's own su process. */
class RootCacheSource(private val shell: RootShell = RootShell()) {
    fun authorize(): String {
        val uid = shell.text("id -u", 128).trim()
        if (uid != "0") throw IOException("Root 未授权：su 返回 UID=$uid")
        return "Root 已授权（UID 0）；只读白名单缓存，不读取 AuthKey"
    }

    fun scan(): RootScan = RootProtocol.parse(shell.text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT))

    fun metadata(path: String, expectedId: String? = WatchfaceXml.pathId(path), identity: String = "缓存路径"): MetadataResult =
        WatchfaceXml.load(path, expectedId, identity) { relative ->
        shell.read(RootPolicy.metadataCommand(path, relative), WatchfaceXml.MAX_BYTES.toLong()) { WatchfaceXml.bounded(it) }
    }

    fun copyInto(directory: File, path: String): File {
        RootPolicy.validatePath(path)
        return shell.read(RootPolicy.readCommand(path)) { input -> WatchfaceFiles.copyInto(directory, input) }
    }
}

data class RootEntry(val name: String, val path: String, val size: Long, val modified: Long)
data class RootScan(val files: List<RootEntry>, val diagnostics: List<String>, val truncated: Boolean)

object RootPolicy {
    val roots = listOf(
        "/storage/emulated/0/Android/data/com.mi.health/files/WatchFace",
        "/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local"
    )
    const val MAX_OUTPUT = 1024 * 1024
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL 不可用于 Shell 参数" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    fun validatePath(path: String) {
        require(roots.any { path.startsWith("$it/") }) { "缓存白名单之外的路径" }
        require("//" !in path && path.split('/').none { it == ".." || it == "." }) { "非规范路径" }
        require(path.endsWith(".bin", true)) { "只允许 BIN 文件" }
        require(path.length <= 4096 && '\u0000' !in path) { "无效路径" }
    }

    // /storage/emulated/0 may itself be Android's legitimate storage alias. Everything
    // below it must be real directories, not links, including the fixed cache roots.
    private val prelude = """
        export LC_ALL=C
        fail() { printf '%s\n' "${'$'}1" >&2; exit 1; }
        rootcheck() {
          local p=/storage/emulated/0 c rest
          rest=${'$'}{1#/storage/emulated/0/}
          while [ -n "${'$'}rest" ]; do
            c=${'$'}{rest%%/*}; p="${'$'}p/${'$'}c"
            [ ! -L "${'$'}p" ] || return 3
            [ -x "${'$'}p" ] && [ -r "${'$'}p" ] || { [ -e "${'$'}p" ] && return 2; [ -x "${'$'}{p%/*}" ] || return 2; return 1; }
            [ "${'$'}rest" != "${'$'}c" ] || break
            rest=${'$'}{rest#*/}
          done
          [ -d "${'$'}1" ] || return 1
        }
        canonicalroot() {
          local storage resolved suffix
          storage=${'$'}(readlink -f /storage/emulated/0) || return 1
          resolved=${'$'}(readlink -f "${'$'}1") || return 1
          suffix=${'$'}{1#/storage/emulated/0/}
          [ "${'$'}resolved" = "${'$'}storage/${'$'}suffix" ] || return 1
          printf '%s' "${'$'}resolved"
        }
    """.trimIndent()

    fun readCommand(path: String): String {
        validatePath(path)
        return fixedReadCommand(path, false)
    }

    fun metadataPath(bin: String, relative: String): String {
        validatePath(bin)
        require(WatchfaceXml.candidate(bin)) { "仅候选 resource.bin 可读取 XML" }
        require(relative in WatchfaceXml.paths) { "仅固定 metadata XML 路径" }
        return bin.substringBeforeLast('/') + "/" + relative
    }

    fun metadataCommand(bin: String, relative: String): String = fixedReadCommand(metadataPath(bin, relative), true)

    private fun fixedReadCommand(path: String, xml: Boolean): String {
        val root = roots.first { path.startsWith("$it/") }
        val limit = if (xml) WatchfaceXml.MAX_BYTES else WatchfaceFiles.MAX_BYTES
        return prelude + "\n" + """
            r=${quote(root)}
            rootcheck "${'$'}r" || fail '缓存根目录不存在、权限拒绝或符号链接'
            base=${'$'}(canonicalroot "${'$'}r") || fail '无法解析根目录'
            p=${quote(path)}
            resolved=${'$'}(readlink -f "${'$'}p") || fail '无法解析文件'
            case "${'$'}resolved" in "${'$'}base"/*) ;; *) fail 'canonical 路径越界';; esac
            rest=${'$'}{p#"${'$'}r"/}; current=${'$'}r
            while [ -n "${'$'}rest" ]; do
              c=${'$'}{rest%%/*}; current="${'$'}current/${'$'}c"
              [ ! -L "${'$'}current" ] || fail '拒绝符号链接'
              [ "${'$'}rest" != "${'$'}c" ] || break
              rest=${'$'}{rest#*/}
            done
            [ -f "${'$'}p" ] && [ -r "${'$'}p" ] || fail '文件不存在或读取被拒绝'
            # Pin the opened file; never re-open the pathname after checking it.
            exec 3< "${'$'}p" || fail '打开文件失败'
            opened=${'$'}(readlink -f /proc/${'$'}${'$'}/fd/3) || fail '无法验证已打开文件'
            [ "${'$'}opened" = "${'$'}resolved" ] || fail '打开期间路径已改变'
            case "${'$'}opened" in "${'$'}base"/*) ;; *) fail '已打开文件越界';; esac
            [ -f /proc/${'$'}${'$'}/fd/3 ] || fail '只允许普通文件'
            size=${'$'}(stat -L -c %s /proc/${'$'}${'$'}/fd/3) || fail 'stat 失败'
            [ "${'$'}size" -le $limit ] || fail '${if (xml) "XML 超过 512 KiB，已跳过" else "文件超过 32 MiB"}'
            ${if (xml) "exec head -c ${WatchfaceXml.MAX_BYTES + 1} <&3" else "exec cat <&3"}
        """.trimIndent()
    }

    fun scanCommand(): String = prelude + "\n" + """
        n=0; count=0; stopped=0
        record() { printf '%s\000%s\000%s\000%s\000' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4"; }
        walk() {
          local p="${'$'}1" depth="${'$'}2" resolved size stamp child
          [ "${'$'}stopped" = 0 ] || return
          n=${'$'}((n+1))
          if [ "${'$'}n" -gt 10000 ] || [ "${'$'}count" -ge 200 ]; then record LIMIT "${'$'}p" entries 0; stopped=1; return; fi
          [ ! -L "${'$'}p" ] || { record LINK "${'$'}p" skipped 0; return; }
          resolved=${'$'}(readlink -f "${'$'}p") || { record DENIED "${'$'}p" canonical 0; return; }
          case "${'$'}resolved" in "${'$'}base"|"${'$'}base"/*) ;; *) record ESCAPE "${'$'}p" skipped 0; return;; esac
          if [ -d "${'$'}p" ]; then
            [ -r "${'$'}p" ] && [ -x "${'$'}p" ] || { record DENIED "${'$'}p" directory 0; return; }
            [ "${'$'}depth" -lt 8 ] || { record LIMIT "${'$'}p" depth 0; return; }
            for child in "${'$'}p"/* "${'$'}p"/.[!.]* "${'$'}p"/..?*; do
              [ -e "${'$'}child" ] || [ -L "${'$'}child" ] || continue
              walk "${'$'}child" "${'$'}((depth+1))"
              [ "${'$'}stopped" = 0 ] || break
            done
          elif [ -f "${'$'}p" ]; then
            case "${'$'}p" in *.[bB][iI][nN]) ;; *) return;; esac
            [ -r "${'$'}p" ] || { record DENIED "${'$'}p" file 0; return; }
            size=${'$'}(stat -c %s "${'$'}p") && stamp=${'$'}(stat -c %Y "${'$'}p") || { record DENIED "${'$'}p" stat 0; return; }
            [ "${'$'}size" -le 33554432 ] || { record LIMIT "${'$'}p" size 0; return; }
            record FILE "${'$'}p" "${'$'}size" "${'$'}stamp"; count=${'$'}((count+1))
          else record MISSING "${'$'}p" vanished 0; fi
        }
    """.trimIndent() + "\n" + roots.joinToString("\n") { root -> """
        r=${quote(root)}
        rootcheck "${'$'}r"; code=${'$'}?
        if [ "${'$'}code" = 0 ]; then
          base=${'$'}(canonicalroot "${'$'}r") || fail 'readlink 不可用'
          walk "${'$'}r" 0
        else
          case "${'$'}code" in 1) record MISSING "${'$'}r" root 0;; 2) record DENIED "${'$'}r" root 0;; *) record LINK "${'$'}r" root 0;; esac
        fi
    """.trimIndent() }
}

object RootProtocol {
    fun parse(text: String): RootScan {
        // Two existing but empty roots legitimately emit no records. RootShell has
        // already verified EOF, exit status and stderr, so this is a successful scan.
        if (text.isEmpty()) return RootScan(emptyList(), emptyList(), false)
        if (text.toByteArray(Charsets.UTF_8).size > RootPolicy.MAX_OUTPUT) throw IOException("Root 扫描输出超限")
        if (!text.endsWith('\u0000')) throw IOException("Root 扫描协议不完整")
        val fields = text.dropLast(1).split('\u0000')
        if (fields.size % 4 != 0) throw IOException("Root 扫描协议损坏")
        val files = mutableListOf<RootEntry>()
        val paths = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        var truncated = false
        for (record in fields.chunked(4)) {
            val (kind, path, a, b) = record
            when (kind) {
                "FILE" -> {
                    try { RootPolicy.validatePath(path) }
                    catch (e: IllegalArgumentException) { throw IOException("扫描路径无效", e) }
                    if (!paths.add(path)) throw IOException("扫描路径重复")
                    val size = a.toLongOrNull() ?: throw IOException("无效大小")
                    val seconds = b.toLongOrNull() ?: throw IOException("无效时间")
                    if (size !in 0..WatchfaceFiles.MAX_BYTES || files.size >= 200 || seconds !in 0..Long.MAX_VALUE / 1000) throw IOException("扫描超限")
                    files += RootEntry(path.substringAfterLast('/'), path, size, seconds * 1000)
                }
                "MISSING", "DENIED", "LIMIT", "LINK", "ESCAPE" -> {
                    if (kind == "LIMIT") truncated = true
                    if (errors.size < 100) errors += "${when (kind) { "MISSING" -> "不存在"; "DENIED" -> "权限拒绝"; "LIMIT" -> "超限"; "LINK" -> "拒绝符号链接"; else -> "路径越界" }}: $path ($a)"
                    else truncated = true
                }
                else -> throw IOException("未知 Root 扫描记录")
            }
        }
        return RootScan(files, errors, truncated)
    }
}

/**
 * No caller sees bytes until EOF, bounded stderr and exit status have been checked.
 * The pipe pump is a daemon: even a descendant retaining a pipe cannot block the
 * caller past its deadline. Buffering is bounded to MAX_BYTES (32 MiB) by default.
 * Call on an IO dispatcher; start is injectable for JVM tests without real su.
 */
class RootShell(
    private val timeoutMillis: Long = 20_000,
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).start() }
) {
    init { require(timeoutMillis in 1..300_000) { "无效 Root 超时" } }

    fun text(command: String, limit: Int): String = read(command, limit.toLong()) { it.readBytes().toString(Charsets.UTF_8) }

    fun <T> read(command: String, limit: Long = WatchfaceFiles.MAX_BYTES, consume: (InputStream) -> T): T {
        require(limit in 0..WatchfaceFiles.MAX_BYTES) { "无效 Root 输出上限" }
        val processRef = AtomicReference<Process?>(null)
        val cancelled = AtomicBoolean(false)
        // Some Process implementations close streams under locks held by a pipe
        // reader. Never perform potentially blocking destruction on the caller.
        fun dispose(process: Process) {
            Thread({
                runCatching { process.destroyForcibly() }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }, "root-cleanup").apply { isDaemon = true; start() }
        }
        val pump = FutureTask<ByteArray> {
            val process = try { start(command) }
            catch (e: IOException) { throw IOException("无法启动 su：请安装 Root 管理器并授权本应用", e) }
            processRef.set(process)
            if (cancelled.get()) {
                dispose(process)
                throw IOException("Root 操作已取消")
            }
            val stderr = ByteArrayOutputStream()
            val errorFailure = AtomicReference<IOException?>(null)
            val errorReader = Thread({
                try {
                    process.errorStream.use { input ->
                        val buffer = ByteArray(1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (stderr.size() + n > 8192) throw IOException("Root stderr 输出超限")
                            stderr.write(buffer, 0, n)
                        }
                    }
                } catch (e: IOException) {
                    errorFailure.set(e)
                    dispose(process)
                }
            }, "root-stderr").apply { isDaemon = true; start() }
            process.outputStream.close()
            val stdout = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (stdout.size().toLong() + n > limit) throw IOException("Root stdout / 文件大小超限")
                    stdout.write(buffer, 0, n)
                }
            }
            val exit = process.waitFor()
            errorReader.join()
            errorFailure.get()?.let { throw it }
            val detail = stderr.toString("UTF-8").trim()
            if (exit != 0 || detail.isNotEmpty()) throw IOException("su 读取失败 (exit=$exit)：$detail；检查本应用 Root 授权、SELinux 与缓存目录权限")
            stdout.toByteArray()
        }
        Thread(pump, "root-stdout").apply { isDaemon = true; start() }
        val bytes = try {
            pump.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("Root 操作超时（授权或扫描/读取未完成）", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Root 操作已取消", e)
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException("Root 读取失败", e.cause))
        } finally {
            cancelled.set(true)
            pump.cancel(true)
            processRef.get()?.let { dispose(it) }
        }
        if (Thread.currentThread().isInterrupted) throw IOException("Root 操作已取消")
        return ByteArrayInputStream(bytes).use(consume)
    }
}
