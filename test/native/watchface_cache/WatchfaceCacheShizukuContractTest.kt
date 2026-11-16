package org.zxor.oronbox

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchfaceCacheShizukuContractTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun canonicalTypedRequestsRoundTripWithoutAcceptingShellSuffixes() {
        val bin = WatchfaceCacheRootPolicy.roots.first() + "/中'\" \$(id); `id`/resource.bin"
        val requests = listOf(
            WatchfaceCacheRequest("scan"), WatchfaceCacheRequest("bin", bin),
            WatchfaceCacheRequest("metadata", bin, "description.xml"),
            WatchfaceCacheRequest("metadata", bin, "resources/manifest.xml"),
            WatchfaceCacheRequest("previews", bin),
            WatchfaceCacheRequest("preview", bin, "resources/_preview/cover.png"),
            WatchfaceCacheRequest("preview", bin, "preview/preview.png"),
        )
        for (request in requests) {
            val decoded = WatchfaceCacheShizukuRequest.decode(request.command())
            assertEquals(request.command(), decoded.command())
            assertEquals(request.limit(), decoded.limit())
            assertThrows(Exception::class.java) { WatchfaceCacheShizukuRequest.decode(request.command() + "\nid") }
        }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRequest("bin", "/etc/passwd").command() }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRequest("metadata", bin, "../../secret.xml").command() }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRequest("exec", bin).command() }
    }

    @Test fun uidSubstitutionTouchesOnlyGuardAndOnlyAllowsRootOrShell() {
        val command = WatchfaceCacheRootPolicy.scanCommand()
        assertEquals(command, WatchfaceCacheRequest.forUid(command, 0))
        val shell = WatchfaceCacheRequest.forUid(command, 2000)
        assertEquals(command.replace("[ \"\$uid\" = 0 ] || exit 77", "[ \"\$uid\" = 2000 ] || exit 77"), shell)
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRequest.forUid(command, 10001) }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRequest.forUid("id", 2000) }
    }

    @Test fun injectedTransportReusesBundleTokensAndOriginalCleanup() {
        val fixture = WatchfaceCacheFixture(temporary.newFolder()).apply { roots(); file(bin, binBytes()) }
        val shell = fixture.shell()
        val requests = mutableListOf<WatchfaceCacheRequest>()
        RootWatchfaceCacheReader(fixture.privateCache, WatchfaceCacheRootShell { throw AssertionError("root must not run") }) { command, limit, budget ->
            val request = WatchfaceCacheShizukuRequest.decode(command)
            assertEquals(request.limit(), limit)
            assertTrue(budget > 0)
            requests += request
            shell.read(command, limit, budget)
        }.use { reader ->
            val token = reader.scan().files.single().token
            val bundle = reader.copyBundle(token)
            val path = bundle["path"] as String
            assertTrue(java.io.File(path).isFile)
            assertTrue(requests.any { it.kind == "scan" })
            assertTrue(requests.any { it.kind == "bin" })
            assertTrue(requests.any { it.kind == "metadata" })
            assertTrue(requests.any { it.kind == "previews" })
            reader.cleanupBundle(path)
            assertFalse(java.io.File(path).parentFile!!.exists())
            reader.scan()
            assertEquals("INVALID_TOKEN", assertThrows(WatchfaceCacheException::class.java) { reader.copyBundle(token) }.code)
        }
    }

    @Test fun rootAuthorizationRunsOnlyFixedUidProbeAndBoundsDenialOutputAndTime() {
        for ((script, expected) in listOf("printf '2000\\n'" to "ROOT_DENIED", "printf '0\\n' >&2" to "ROOT_STDERR",
            "printf '00000000000000000'" to "OUTPUT_LIMIT", "exec sleep 3" to "ROOT_TIMEOUT")) {
            val shell = WatchfaceCacheRootShell(100) { command ->
                assertEquals("id -u", command)
                ProcessBuilder("bash", "-c", script).start()
            }
            assertEquals(expected, assertThrows(WatchfaceCacheException::class.java) { shell.authorize() }.code)
        }
        assertTrue(WatchfaceCacheRootShell { command ->
            assertEquals("id -u", command)
            ProcessBuilder("bash", "-c", "printf '0\\n'").start()
        }.authorize())
    }

    private class Reply : MethodChannel.Result {
        var value: Any? = null
        var code: String? = null
        var count = 0
        override fun success(result: Any?) { value = result; count++ }
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) { code = errorCode; count++ }
        override fun notImplemented() { count++ }
    }

    @Test fun bridgeModeAndAuthorizationAreBackwardCompatibleAndCloseOnlyRepliesOnce() {
        val fixture = WatchfaceCacheFixture(temporary.newFolder()).apply { roots(); file(bin, binBytes()) }
        val channel = MethodChannel(object : BinaryMessenger {}, PlatformChannelNames.WATCHFACE_CACHE)
        val callbacks = LinkedBlockingQueue<Runnable>()
        val executor = Executors.newSingleThreadExecutor()
        val modes = mutableListOf<String>()
        var authorization: ((Result<Any?>) -> Unit)? = null
        var backendCloses = 0
        var rootAuthorizations = 0
        val bridge = WatchfaceCacheBridge(channel, fixture.reader(), executor, { callbacks.offer(it) }, { callbacks.clear() }, { throw AssertionError(it) },
            { modes += it }, { authorization = it }, { backendCloses++ }, { rootAuthorizations++; true })
        fun call(method: String, args: Any? = null): Reply = Reply().also {
            channel.handler!!.onMethodCall(MethodCall(method, args), it)
        }
        fun deliver() { (callbacks.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no result")).run() }
        try {
            val root = call("scan"); deliver(); assertNull(root.code)
            val shizuku = call("scan", mapOf("mode" to "shizuku")); deliver(); assertNull(shizuku.code)
            assertEquals(listOf("root", "shizuku"), modes)
            val invalid = call("scan", mapOf("mode" to 42)); deliver(); assertEquals("INVALID_ARGUMENT", invalid.code)
            assertEquals(2, modes.size)
            val defaultRoot = call("authorize"); deliver()
            assertEquals(true, defaultRoot.value)
            assertEquals(1, rootAuthorizations)
            assertNull(authorization)
            val explicitRoot = call("authorize", mapOf("mode" to "root")); deliver()
            assertEquals(true, explicitRoot.value)
            assertEquals(2, rootAuthorizations)
            val invalidAuthorization = call("authorize", mapOf("mode" to "bad")); deliver()
            assertEquals("INVALID_ARGUMENT", invalidAuthorization.code)
            assertEquals(2, rootAuthorizations)
            val authorized = call("authorize", mapOf("mode" to "shizuku"))
            assertEquals(0, authorized.count)
            authorization!!(Result.success(true)); deliver()
            assertEquals(true, authorized.value)
            assertEquals(1, authorized.count)
            val late = call("authorize", mapOf("mode" to "shizuku"))
            bridge.close()
            assertEquals("CANCELLED", late.code)
            authorization!!(Result.success(true))
            assertTrue(callbacks.isEmpty())
            assertEquals(1, late.count)
            assertEquals(1, backendCloses)
        } finally {
            bridge.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
