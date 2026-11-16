// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.xiaom.watchfaceinstaller.classic

import java.util.UUID

/** Fixed Gadgetbridge 221ae4eb08423e113968145f7b93ec8303280295 Xiaomi SPP profile. */
object ClassicProfile {
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    const val SECURE_SOCKET = true
}
enum class ClassicPhase {
    IDLE, BONDED_CHECK, DISCOVERY_CANCEL, SOCKET_CONNECT, VERSION_WRITE, VERSION_READ, SESSION_CONFIG,
    AUTH_NONCE, AUTH_PROOF, LIST_CONFIRM, READY, PREPARE, UPLOAD, VERIFY, ACTIVATE
}
/** Transport supplies a precise stage without retaining device/key data in its own message. */
class ClassicTransportFailure(val phase: ClassicPhase, cause: Throwable, val snapshot: ClassicTransportSnapshot = ClassicTransportSnapshot()) :
    java.io.IOException("Classic transport $phase", cause)

data class ClassicFailure(val phase: ClassicPhase, val causes: List<Cause>, val transport: ClassicTransportSnapshot = ClassicTransportSnapshot()) {
    data class Cause(val type: String, val message: String)
    fun diagnostic(): String = "phase=$phase\n${transport.diagnostic()}\n" + causes.joinToString("\ncaused-by: ") { "${it.type}: ${it.message}" }
    companion object {
        fun capture(phase: ClassicPhase, error: Throwable, snapshot: ClassicTransportSnapshot = ClassicTransportSnapshot()): ClassicFailure {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val chain = mutableListOf<Cause>()
            var current: Throwable? = error
            var actual = phase
            var observed = snapshot
            while (current != null && chain.size < 5 && seen.add(current)) {
                val e = current
                if (e is ClassicTransportFailure) { actual = e.phase; observed = e.snapshot }
                val type = when (e) {
                    is ClassicTransportFailure -> "ClassicTransportFailure"
                    is SecurityException -> "SecurityException"
                    is java.io.EOFException -> "EOFException"
                    is java.net.SocketTimeoutException -> "Timeout"
                    is java.io.IOException -> "IOException"
                    is kotlinx.coroutines.TimeoutCancellationException -> "Timeout"
                    is kotlinx.coroutines.CancellationException -> "Cancelled"
                    is IllegalArgumentException -> "InvalidResponse"
                    else -> "ProtocolError"
                }
                // Arbitrary parser/crypto messages may contain binary payloads; do not export them.
                chain += Cause(type, if (e is java.io.IOException) ClassicRedaction.text(e.message ?: "I/O error") else type)
                current = e.cause
            }
            return ClassicFailure(actual, chain.toList(), observed)
        }
    }
}
object ClassicRedaction {
    private val mac = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    private val hex = Regex("(?i)[0-9a-f]{12,}")
    private val spacedHex = Regex("(?i)(?:[0-9a-f]{2}[ ,:;\\-]+){7,}[0-9a-f]{2}")
    private val secret = Regex("(?i)(?:authkey|encrypt_key|key|nonce|proof|payload|bin)\\s*[:=]\\s*[^\\s,;]+")
    fun text(input: String): String {
        if (input.length > 8192) return "[oversized message omitted]"
        return secret.replace(hex.replace(spacedHex.replace(mac.replace(input, "[MAC]"), "[HEX]"), "[HEX]"), "[SECRET]")
            .map { if (it.isISOControl() || it == '\u202e' || it == '\u202d') ' ' else it }.joinToString("").take(240)
    }
}
