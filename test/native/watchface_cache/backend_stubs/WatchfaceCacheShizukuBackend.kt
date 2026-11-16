package org.zxor.oronbox
import android.content.Context
import android.os.Handler
import java.io.Closeable
// Existing JVM tests never instantiate attach(context); compile the real backend separately against Android.
internal class WatchfaceCacheShizukuBackend(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") handler: Handler): Closeable {
 fun read(@Suppress("UNUSED_PARAMETER") command: String, @Suppress("UNUSED_PARAMETER") limit: Long, @Suppress("UNUSED_PARAMETER") budget: Long): ByteArray = throw AssertionError("not used by existing JVM tests")
 fun authorize(reply: (Result<Any?>) -> Unit) { reply(Result.failure(WatchfaceCacheException("SHIZUKU_UNAVAILABLE"))) }
 override fun close() {}
}
