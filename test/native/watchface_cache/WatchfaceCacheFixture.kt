package org.zxor.oronbox

import java.io.File
import java.nio.file.Files

/** All sources are ordinary files below a JVM temporary folder; tests never start su. */
internal class WatchfaceCacheFixture(val directory: File) {
    val storage = File(directory, "storage").apply { mkdirs() }
    val privateCache = File(directory, "private").apply { mkdirs() }
    private val anchor = "/storage/emulated/0"
    val root get() = WatchfaceCacheRootPolicy.roots.first()
    val id = "120917345196"
    val bin get() = "$root/$id/resource.bin"
    fun mapped(path: String) = File(path.replace(anchor, storage.path))
    fun roots() = WatchfaceCacheRootPolicy.roots.forEach { mapped(it).mkdirs() }
    fun file(path: String, bytes: ByteArray): File = mapped(path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    fun binBytes(name: String = "BIN name", idValue: String = id): ByteArray = ByteArray(0xA8).apply {
        byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(this)
        idValue.toByteArray().copyInto(this, 0x28)
        name.toByteArray().copyInto(this, 0x68)
    }
    fun png() = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    fun jpeg() = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 1, 2, 3)
    fun link(path: String, target: File) = Files.createSymbolicLink(mapped(path).toPath(), target.toPath())
    fun shell(timeout: Long = System.getenv("WATCHFACE_TEST_TIMEOUT_MS")?.toLong() ?: 5000, prefix: String = "") = WatchfaceCacheRootShell(timeout) { command ->
        var translated = prefix + command.replace(anchor, storage.path)
        if (command == WatchfaceCacheRootPolicy.scanCommand()) {
            // Restore source paths only in scan records, never in BIN/XML/image bytes.
            translated = "set -o pipefail\n{\n$translated\n} | sed 's|${storage.path}|$anchor|g'"
        }
        ProcessBuilder("bash", "-c", translated).directory(directory).start()
    }
    fun reader(shell: WatchfaceCacheRootShell = shell()) = RootWatchfaceCacheReader(privateCache, shell)
}
