package org.zxor.oronbox

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchfaceCacheBridgeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Result : MethodChannel.Result {
        val count = AtomicInteger()
        var value: Any? = null
        var code: String? = null
        var unimplemented = false
        override fun success(result: Any?) { value = result; count.incrementAndGet() }
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            code = errorCode; count.incrementAndGet()
        }
        override fun notImplemented() { unimplemented = true; count.incrementAndGet() }
    }

    private inner class Harness(
        val fixture: WatchfaceCacheFixture = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() },
        reader: RootWatchfaceCacheReader = fixture.reader(),
        postOverride: ((Runnable) -> Boolean)? = null,
    ) : AutoCloseable {
        val channel = MethodChannel(object : BinaryMessenger {}, PlatformChannelNames.WATCHFACE_CACHE)
        val callbacks = LinkedBlockingQueue<Runnable>()
        val cleanupErrors = CopyOnWriteArrayList<Exception>()
        val cleanupThreads = CopyOnWriteArrayList<Thread>()
        val workerThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "bridge-test-worker").also(workerThread::set)
        }
        val bridge = WatchfaceCacheBridge(
            channel, reader, executor,
            postOverride ?: { callbacks.offer(it) },
            { callbacks.clear() },
            { cleanupErrors += it; cleanupThreads += Thread.currentThread() },
        )
        fun call(method: String, args: Any? = null): Result {
            val result = Result()
            channel.handler!!.onMethodCall(MethodCall(method, args), result)
            return result
        }
        fun deliver() {
            val callback = callbacks.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback")
            callback.run()
        }
        override fun close() {
            bridge.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun token(result: Result): String =
        (((result.value as Map<String, Any?>)["files"] as List<Map<String, Any?>>).single()["token"] as String)

    @Test fun registersExactChannelAndDispatchesScanBundleDeleteAndLegacyCopy() {
        Harness().use { h ->
            h.fixture.file(h.fixture.bin, h.fixture.binBytes())
            assertEquals("oronbox/watchface_cache", h.channel.name)
            assertNotNull(h.channel.handler)
            val scan = h.call("scan")
            assertEquals(0, scan.count.get())
            h.deliver()
            assertNull(scan.code)
            val bundle = h.call("copyBundle", mapOf("token" to token(scan)))
            h.deliver()
            @Suppress("UNCHECKED_CAST") val value = bundle.value as Map<String, Any?>
            assertEquals(setOf("path", "name", "embeddedId", "previews", "warnings", "nameSource"), value.keys)
            val path = value["path"] as String
            assertTrue(File(path).isFile)
            val delete = h.call("deleteCopy", mapOf("path" to path))
            h.deliver()
            assertNull(delete.code)
            assertEquals(1, delete.count.get())
            assertFalse(File(path).exists())
            val legacy = h.call("copy", mapOf("token" to token(scan)))
            h.deliver()
            assertTrue(File(legacy.value as String).isFile)
        }
    }

    @Test fun invalidArgumentsUnknownMethodAndUnknownTokenProduceSingleReply() {
        Harness().use { h ->
            for (method in listOf("copyBundle", "copy", "deleteCopy")) {
                val key = if (method == "deleteCopy") "path" else "token"
                for (args in listOf(null, "bad map", emptyMap<String, Any>(), mapOf(key to 42),
                    mapOf(key to " "), mapOf(key to "x\u0000y"))) {
                    val result = h.call(method, args)
                    h.deliver()
                    assertEquals("INVALID_ARGUMENT", result.code)
                    assertEquals(1, result.count.get())
                }
            }
            val unknown = h.call("unknown")
            h.deliver()
            assertTrue(unknown.unimplemented)
            assertEquals(1, unknown.count.get())
            val token = h.call("copyBundle", mapOf("token" to "not-a-token"))
            h.deliver()
            assertEquals("INVALID_TOKEN", token.code)
            val path = h.call("deleteCopy", mapOf("path" to "/etc/never-delete"))
            h.deliver()
            assertEquals("UNOWNED_BUNDLE", path.code)
        }
    }

    @Test fun rootFailuresReachFlutterWithOriginalCodes() {
        val f = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
        for ((script, expected) in listOf("exit 77" to "ROOT_DENIED", "exit 42" to "DENIED", "exec sleep 2" to "ROOT_TIMEOUT")) {
            val shell = WatchfaceCacheRootShell(100) { ProcessBuilder("bash", "-c", script).start() }
            Harness(f, f.reader(shell)).use { h ->
                val result = h.call("scan")
                h.deliver()
                assertEquals(expected, result.code)
                assertEquals(1, result.count.get())
            }
        }
        Harness(f, f.reader(WatchfaceCacheRootShell { throw IOException("su missing") })).use { h ->
            val result = h.call("scan")
            h.deliver()
            assertEquals("ROOT_UNAVAILABLE", result.code)
        }
    }

    @Test fun closeDropsAlreadyPostedResultsUnregistersAndCleansBundlesOnWorker() {
        val f = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
        val source = f.file(f.bin, f.binBytes())
        val original = source.readBytes()
        val reader = f.reader()
        val registered = reader.copyBundle(reader.scan().files.single().token)
        val bin = File(registered["path"] as String)
        val h = Harness(f, reader)
        val capturedHandler = h.channel.handler!!
        val result = h.call("scan")
        val lateCallback = h.callbacks.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback")
        h.close()
        assertNull(h.channel.handler)
        assertFalse(bin.parentFile!!.exists())
        assertTrue(h.cleanupErrors.isEmpty())
        assertEquals("CANCELLED", result.code)
        assertEquals(1, result.count.get())
        lateCallback.run()
        capturedHandler.onMethodCall(MethodCall("scan", null), result)
        assertEquals(1, result.count.get())
        assertArrayEquals(original, source.readBytes())
        h.bridge.close()
    }

    @Test fun closeInterruptsActiveRootReadCancelsQueuedRequestsThenCleansOnSameWorker() {
        val f = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
        f.file(f.bin, f.binBytes())
        val worker = AtomicReference<Thread>()
        val launches = AtomicInteger()
        val started = CountDownLatch(1)
        // The first scan/copy below creates a registered session; later Root reads block until cancelled.
        val switch = AtomicInteger()
        val shell = WatchfaceCacheRootShell(5000) { command ->
            if (switch.get() == 0) {
                // Match fixture translation without touching real source roots.
                val script = command.replace("/storage/emulated/0", f.storage.path)
                val translated = if (command == WatchfaceCacheRootPolicy.scanCommand())
                    "set -o pipefail\n{\n$script\n} | sed 's|${f.storage.path}|/storage/emulated/0|g'" else script
                ProcessBuilder("bash", "-c", translated).start()
            } else {
                launches.incrementAndGet()
                started.countDown()
                ProcessBuilder("bash", "-c", "exec sleep 10").start()
            }
        }
        val reader = f.reader(shell)
        val bundle = reader.copyBundle(reader.scan().files.single().token)
        switch.set(1)
        val h = Harness(f, reader)
        val active = h.call("scan")
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queued = h.call("scan")
        worker.set(h.workerThread.get())
        val before = System.nanoTime()
        h.close()
        assertTrue((System.nanoTime() - before) / 1_000_000 < 2000)
        assertEquals(1, launches.get())
        assertEquals("CANCELLED", active.code)
        assertEquals("CANCELLED", queued.code)
        assertEquals(1, active.count.get())
        assertEquals(1, queued.count.get())
        assertTrue(h.callbacks.isEmpty())
        assertFalse(File(bundle["path"] as String).parentFile!!.exists())
        assertTrue(h.cleanupErrors.isEmpty())
        assertNotEquals(Thread.currentThread(), worker.get())
    }

    @Test fun closeCleansACompletedButUndeliveredBundle() {
        Harness().use { h ->
            h.fixture.file(h.fixture.bin, h.fixture.binBytes())
            val scan = h.call("scan")
            h.deliver()
            val bundle = h.call("copyBundle", mapOf("token" to token(scan)))
            val callback = h.callbacks.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback")
            val base = File(h.fixture.privateCache, "watchface_cache_import")
            assertEquals(1, base.listFiles()!!.size)
            h.close()
            callback.run()
            assertEquals("CANCELLED", bundle.code)
            assertEquals(1, bundle.count.get())
            assertTrue(base.listFiles()!!.isEmpty())
        }
    }

    @Test fun cleanupFailureIsReportedOnWorkerAndDoesNotSkipOtherSessions() {
        val f = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
        f.file(f.bin, f.binBytes())
        val reader = f.reader()
        val token = reader.scan().files.single().token
        val first = File(reader.copyBundle(token)["path"] as String)
        val second = File(reader.copyBundle(token)["path"] as String)
        val foreign = File(first.parentFile, "foreign.txt").apply { writeText("preserve") }
        val h = Harness(f, reader)
        h.close()
        assertEquals(1, h.cleanupErrors.size)
        assertEquals(h.workerThread.get(), h.cleanupThreads.single())
        assertEquals("PRIVATE_CACHE_CHANGED", (h.cleanupErrors.single() as WatchfaceCacheException).code)
        assertEquals("preserve", foreign.readText())
        assertFalse(second.parentFile!!.exists())
        foreign.delete()
        reader.close()
    }

    @Test fun refusedMainQueueAndExecutorRejectionDoNotLeakOrDoubleReply() {
        val f = WatchfaceCacheFixture(temporary.newFolder()).apply { roots() }
        Harness(f, postOverride = { false }).use { h ->
            val result = h.call("unknown")
            assertEquals("CANCELLED", result.code)
            assertEquals(1, result.count.get())
            assertTrue(h.callbacks.isEmpty())
        }
        val h = Harness()
        h.executor.shutdown()
        val result = h.call("scan")
        h.deliver()
        assertEquals("CANCELLED", result.code)
        h.bridge.close()
        assertEquals(1, h.cleanupErrors.size)
    }
}
