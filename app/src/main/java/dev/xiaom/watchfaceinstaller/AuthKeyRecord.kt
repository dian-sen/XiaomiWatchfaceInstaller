package dev.xiaom.watchfaceinstaller

import org.json.JSONObject
import java.io.Closeable

/** Deliberately not a data class: no generated toString/copy exposing the secret. */
class AuthKeyRecord private constructor(val model: String, val mac: String, private val key: CharArray) : Closeable {
    override fun close() { key.fill('\u0000') }
    companion object {
        const val MODEL = "miwear.watch.n67cn"
        private val HEX = Regex("^[0-9a-fA-F]{32}$")
        private val MAC = Regex("^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        fun parse(model: String, detail: String): AuthKeyRecord {
            // JSONObject necessarily creates temporary Strings; never persist or log them.
            require(model == MODEL && detail.length <= 65536) { "设备记录校验失败" }
            try {
                val json = JSONObject(detail)
                val key = json.opt("encrypt_key") as? String
                val mac = json.opt("mac") as? String
                require(key != null && HEX.matches(key) && mac != null && MAC.matches(mac))
                return AuthKeyRecord(model, mac.uppercase(), key.toCharArray())
            } catch (_: Exception) { throw IllegalArgumentException("设备记录校验失败") }
        }
    }
}

/** No external path, model, shell text or UI value is accepted by this command builder. */
object AuthKeyCommands {
    const val DATABASE = "/data/user/0/com.mi.health/databases/device_db"
    const val BASE_LIMIT = 16L * 1024 * 1024
    const val WAL_LIMIT = 32L * 1024 * 1024
    private fun quote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
    fun probe(namespace: Boolean): String = wrap("[ \"\$(id -u)\" = 0 ] && [ -f '$DATABASE' ] && [ -r '$DATABASE' ] && printf 'AUTH_ROOT_OK\\n'", namespace)
    fun snapshot(namespace: Boolean): String = wrap("""
        set -eu
        [ "${'$'}(id -u)" = 0 ] || exit 1
        for p in /data /data/user /data/user/0/com.mi.health /data/user/0/com.mi.health/databases '$DATABASE'; do
          [ ! -L "${'$'}p" ] || exit 1
        done
        resolved=${'$'}(readlink -f '$DATABASE')
        case "${'$'}resolved" in
          /data/user/0/com.mi.health/databases/device_db|/data/data/com.mi.health/databases/device_db) ;;
          *) exit 1 ;;
        esac
        # Rollback journals are not part of the supported WAL snapshot format.
        [ ! -e '${DATABASE}-journal' ] || exit 1
        b='$DATABASE'; w='${DATABASE}-wal'
        [ -f "${'$'}b" ] && [ -r "${'$'}b" ] || exit 1
        [ ! -L "${'$'}w" ] || exit 1
        n=${'$'}(stat -c %s "${'$'}b")
        [ "${'$'}n" -gt 0 ] && [ "${'$'}n" -le $BASE_LIMIT ] || exit 1
        m=0; present=0
        if [ -e "${'$'}w" ]; then
          [ -f "${'$'}w" ] && [ -r "${'$'}w" ] || exit 1
          m=${'$'}(stat -c %s "${'$'}w"); present=1
          [ "${'$'}m" -le $WAL_LIMIT ] || exit 1
        fi
        printf '%s\n%s\n%s\n' "${'$'}n" "${'$'}m" "${'$'}present"
        head -c "${'$'}n" "${'$'}b"
        if [ "${'$'}present" = 1 ]; then head -c "${'$'}m" "${'$'}w"; fi
        [ ! -e '${DATABASE}-journal' ] || exit 1
        [ "${'$'}(stat -c %s "${'$'}b")" = "${'$'}n" ] || exit 1
        if [ "${'$'}present" = 1 ]; then
          [ "${'$'}(stat -c %s "${'$'}w")" = "${'$'}m" ] || exit 1
        else [ ! -e "${'$'}w" ] || exit 1
        fi
    """.trimIndent(), namespace)
    private fun wrap(script: String, namespace: Boolean): String =
        if (namespace) "nsenter -t 1 -m -- /system/bin/sh -c ${quote(script)}" else script
}
