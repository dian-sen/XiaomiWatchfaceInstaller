// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Gadgetbridge XiaomiSppSupport/ProtocolV1/ProtocolV2, Auth and upload/watchface services.
// Original authors and fixed source revision: ProtocolLicense.txt, docs/CLASSIC-NOTICE.md.
package dev.xiaom.watchfaceinstaller.classic

import dev.xiaom.watchfaceinstaller.WatchfaceIdEditor
import dev.xiaom.watchfaceinstaller.ble.XiaomiFrames
import dev.xiaom.watchfaceinstaller.ble.XiaomiProtoWire as P
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** User-triggered, single-use experimental session. No root access, key lookup, or reconnect. */
class XiaomiClassicSession(
    private val transport: ClassicTransport,
    private val phoneApi: Int = 35,
    private val phoneModel: String = "Android",
    private val region: String = "CN",
    private val responseTimeoutMs: Long = 20_000,
    private val ackTimeoutMs: Long = 10_000
) : Closeable {
    enum class State { IDLE, CONNECTING, NEGOTIATING, AUTHENTICATING, READY, PREPARING, UPLOADING, VERIFYING, SUCCEEDED, FAILED, CLOSED }
    data class Progress(val phase: String, val completed: Int, val total: Int)
    data class InstallResult(val id: String, val protocolVersion: Int, val activeConfirmed: Boolean)
    private val mutableState = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = mutableState.asStateFlow()
    @Volatile private var lastPhase = ClassicPhase.IDLE
    @Volatile private var lastFailure: ClassicFailure? = null
    /** Synchronous snapshots: independent of StateFlow collector scheduling; retained on close. */
    val phase: ClassicPhase get() = lastPhase
    val failure: ClassicFailure? get() = lastFailure
    private fun stage(value: ClassicPhase) { lastPhase = value }
    private val operation = Mutex()
    private val stateLock = Any()
    private val stopped = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incoming = Channel<ByteArray>(32)
    private val decoder = XiaomiClassicWire.Decoder()
    private val commands = ArrayDeque<List<P.Field>>()
    private var crypto: XiaomiClassicCrypto? = null
    private var version = 1
    private var serial = 0
    private var counter = 0
    private var authenticated = false
    private var expectedRx: Int? = null
    private var lastRx: XiaomiClassicWire.Frame? = null
    private var maximumV2Frame = 64512
    init { require(responseTimeoutMs > 0 && ackTimeoutMs > 0) }

    private fun transition(next: State) = synchronized(stateLock) {
        check(!stopped.get() && state.value != State.CLOSED) { "连接已结束" }; mutableState.value = next
    }
    private suspend fun write(bytes: ByteArray) {
        currentCoroutineContext().ensureActive()
        check(!stopped.get()) { "连接已结束" }
        withTimeout(responseTimeoutMs) { transport.write(bytes) }
    }
    private suspend fun frame(): XiaomiClassicWire.Frame {
        while (true) {
            decoder.pop(version)?.let { return it }
            decoder.feed(incoming.receive())
        }
    }
    private fun nested(fields: List<P.Field>, field: Int) = P.parse(P.one(fields, field, 2).bytes)
    private fun number(fields: List<P.Field>, field: Int) = P.one(fields, field, 0).value
    private fun optional(fields: List<P.Field>, field: Int, default: Long) =
        if (fields.any { it.number == field }) number(fields, field) else default
    private fun acceptCommand(bytes: ByteArray) {
        val fields = P.parse(bytes)
        number(fields, 1); optional(fields, 2, 0) // proto2 optional subtype defaults to zero
        require(optional(fields, 100, 0) == 0L) { "设备返回命令错误状态" }
        check(commands.size < 64) { "设备命令队列超限" }
        commands.addLast(fields)
    }
    /** Processes unsolicited/data frames while waiting for an ACK or command. */
    private suspend fun receiveData(f: XiaomiClassicWire.Frame) {
        if (version == 1) {
            // No known V1 response encoding in the pinned upstream (explicit TODO).
            // Do not invent a BLE ACK. Refuse this unsupported variant instead.
            require(f.flags and 0x40 == 0) { "设备要求未定义的 SPP V1 response；已停止，未伪造 ACK" }
            require(f.opcode == 2 && f.kind in 0..2) { "不支持的 SPP V1 帧" }
            if (f.channel != 1) return
            require(!authenticated || f.kind == 1) { "认证后收到明文命令，已停止" }
            acceptCommand(if (f.kind == 1) checkNotNull(crypto).decryptV1(f.payload) else f.payload)
            return
        }
        require(f.kind == 3) { "意外的 SPP V2 控制帧" }
        val previous = lastRx
        if (previous != null && f.serial == previous.serial) {
            require(f.payload.contentEquals(previous.payload)) { "重复序号的内容变化" }
            write(XiaomiClassicWire.v2(1, f.serial)); return
        }
        if (expectedRx != null && f.serial != expectedRx) {
            write(XiaomiClassicWire.v2(0, checkNotNull(expectedRx))); return
        }
        require(f.payload.size >= 2) { "SPP V2 数据头截断" }
        val channel = f.payload[0].toInt() and 255
        val opcode = f.payload[1].toInt() and 255
        require(channel in listOf(1, 2, 5) && opcode in 1..2) { "不支持的 SPP V2 数据类型" }
        if (channel == 1) {
            require(!authenticated || opcode == 2) { "认证后收到明文命令，已停止" }
            val payload = f.payload.copyOfRange(2, f.payload.size)
            acceptCommand(if (opcode == 2) checkNotNull(crypto).decryptV2(payload) else payload)
        }
        lastRx = f; expectedRx = (f.serial + 1) and 255
        write(XiaomiClassicWire.v2(1, f.serial))
    }
    private suspend fun send(payload: ByteArray, auth: Boolean = false, data: Boolean = false) {
        if (version == 1) {
            val body: ByteArray
            val kind: Int
            if (auth) { body = payload; kind = 2 }
            else {
                check(authenticated)
                if (!data) { check(counter < 65535) { "SPP V1 加密计数耗尽，需要新会话" }; counter++ }
                body = (if (data) byteArrayOf() else XiaomiFrames.le16(counter)) +
                    checkNotNull(crypto).encryptV1(payload, if (data) 0 else counter)
                kind = 1
            }
            write(XiaomiClassicWire.v1(if (data) 5 else 2, serial = serial, kind = kind, payload = body))
            serial = (serial + 1) and 255
            return // V1 uses ordered/reliable RFCOMM; no invented application ACK/retry.
        }
        check(auth || authenticated)
        val body = byteArrayOf(if (data) 2 else 1, if (auth || data) 1 else 2) +
            (if (auth || data) payload else checkNotNull(crypto).encryptV2(payload))
        val encoded = XiaomiClassicWire.v2(3, serial, body)
        require(encoded.size <= maximumV2Frame) { "数据超过设备协商的 SPP V2 帧上限" }
        // Stop-and-wait is a one-frame go-back-N window. Retry identical ciphertext/sequence,
        // never re-encrypt/reissue the logical upload or installation command.
        repeat(3) {
            write(encoded)
            val acked = withTimeoutOrNull(ackTimeoutMs) {
                while (true) {
                    val f = frame()
                    if (f.kind == 1 && f.serial == serial) return@withTimeoutOrNull true
                    if (f.kind == 0) {
                        if (f.serial == ((serial + 1) and 255)) return@withTimeoutOrNull true
                        if (f.serial == serial) return@withTimeoutOrNull false
                    }
                    if (f.kind >= 2) receiveData(f)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (acked == true) { serial = (serial + 1) and 255; return }
        }
        error("SPP V2 未收到 ACK，已停止；不会自动重连或重刷")
    }
    private suspend fun response(type: Int, subtype: Int): List<P.Field> = withTimeout(responseTimeoutMs) {
        var skipped = 0
        while (true) {
            while (commands.isNotEmpty()) {
                val cmd = commands.removeFirst()
                if (number(cmd, 1) == type.toLong() && optional(cmd, 2, 0) == subtype.toLong()) return@withTimeout cmd
                require(++skipped <= 64) { "未收到匹配的设备响应" }
            }
            val f = frame()
            if (version == 1 || f.kind >= 2) receiveData(f)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }
    private fun configureV2(payload: ByteArray) {
        require(payload.isNotEmpty() && payload[0] == 2.toByte()) { "设备未确认 SPP V2 会话" }
        var offset = 1
        val seen = mutableSetOf<Int>()
        while (offset < payload.size) {
            require(payload.size - offset >= 3)
            val field = payload[offset].toInt() and 255
            val length = XiaomiFrames.read16(payload, offset + 1); offset += 3
            require(length <= payload.size - offset && seen.add(field)) { "SPP V2 会话参数错误" }
            when (field) {
                1 -> require(length == 3 && payload[offset] == 1.toByte() && payload[offset + 1] == 0.toByte() && payload[offset + 2] == 0.toByte()) { "不支持的 SPP V2 会话版本" }
                2 -> { require(length == 2); maximumV2Frame = minOf(64512, XiaomiFrames.read16(payload, offset)); require(maximumV2Frame >= 256) }
                3 -> { require(length == 2 && XiaomiFrames.read16(payload, offset) >= 1) }
                4 -> require(length == 2)
            }
            offset += length
        }
    }
    suspend fun connectAndAuthenticate(mac: String, key: ByteArray) {
        try {
            runOperation {
                synchronized(stateLock) {
                    check(state.value == State.IDLE && !stopped.get())
                    require(key.size == 16 && mac.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "认证参数无效" }
                    crypto = XiaomiClassicCrypto(key)
                }
                key.fill(0)
                transition(State.CONNECTING)
                stage(ClassicPhase.SOCKET_CONNECT)
                withTimeout(responseTimeoutMs) { transport.connect(mac) }
                scope.launch {
                    try { while (isActive) incoming.send(transport.read()) }
                    catch (e: Throwable) { incoming.close(e) }
                }
                transition(State.NEGOTIATING)
                stage(ClassicPhase.VERSION_WRITE)
                write(XiaomiClassicWire.versionRequest)
                stage(ClassicPhase.VERSION_READ)
                val answer = withTimeout(responseTimeoutMs) { frame() }
                require(answer.channel == 0 && answer.kind == 0 && answer.payload.isNotEmpty()) { "未收到明确的 SPP 版本；不会超时猜测回退" }
                version = answer.payload[0].toInt() and 255
                require(version in 1..2) { "设备 SPP 版本 $version 未支持；不会回退" }
                if (version == 2) {
                    stage(ClassicPhase.SESSION_CONFIG)
                    write(XiaomiClassicWire.sessionRequest)
                    val config = withTimeout(responseTimeoutMs) { frame() }
                    require(config.kind == 2)
                    configureV2(config.payload)
                }
                transition(State.AUTHENTICATING)
                stage(ClassicPhase.AUTH_NONCE)
                send(checkNotNull(crypto).hello(), auth = true)
                val verify = nested(nested(response(1, 26), 3), 31)
                stage(ClassicPhase.AUTH_PROOF)
                val confirm = checkNotNull(crypto).confirm(P.one(verify, 1, 2).bytes, P.one(verify, 2, 2).bytes,
                    P.phoneInfo(phoneApi, phoneModel, region))
                send(confirm, auth = true)
                response(1, 27)
                // Upstream accepts subtype 27 alone. Do not assign undocumented meaning to
                // DeviceConfirm.unknown1 or plaintext user-id status during encrypted auth.
                // Explicit top-level errors were checked in acceptCommand; prove the encrypted
                // session by a successful watchface-list round trip before reporting READY.
                authenticated = true
                listWatchfaces()
                stage(ClassicPhase.READY)
                transition(State.READY)
            }
        } finally { key.fill(0) }
    }
    private suspend fun listWatchfaces(): Map<String, Boolean> {
        stage(ClassicPhase.LIST_CONFIRM)
        // Discard old unsolicited lists before issuing a new request. Never treat cached list as proof.
        commands.removeAll { number(it, 1) == 4L && optional(it, 2, 0) == 0L }
        send(P.command(4, 0))
        val list = nested(nested(response(4, 0), 6), 1)
        val result = linkedMapOf<String, Boolean>()
        for (entry in list.filter { it.number == 1 }) {
            require(entry.wire == 2)
            val fields = P.parse(entry.bytes)
            val id = String(P.one(fields, 1, 2).bytes, Charsets.US_ASCII)
            require(id.length in 1..64 && !result.containsKey(id)) { "设备表盘列表无效" }
            result[id] = optional(fields, 3, 0) == 1L
        }
        return result
    }
    suspend fun install(file: File, onProgress: (Progress) -> Unit = {}): InstallResult = runOperation {
        check(state.value == State.READY && authenticated) { "需要先完成连接认证" }
        withTimeout(5 * 60_000L) {
            // Snapshot once: prepare length, MD5, CRC and uploaded bytes all refer to this snapshot.
            require(file.isFile && file.length() in 0xa8..WatchfaceIdEditor.MAX_BYTES) { "表盘文件长度无效" }
            val bytes = file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size().toLong() + n <= WatchfaceIdEditor.MAX_BYTES) { "表盘文件过大" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val id = WatchfaceIdEditor.validatedId(bytes)
            stage(ClassicPhase.PREPARE); transition(State.PREPARING)
            require(id !in listWatchfaces()) { "设备已存在同 ID 表盘；拒绝覆盖，请使用新的 ID 副本" }
            stage(ClassicPhase.PREPARE)
            send(P.installStart(id, bytes.size))
            require(optional(nested(response(4, 4), 6), 5, 0) == 0L) { "设备拒绝准备安装" }
            send(P.uploadStart(bytes))
            val ack = nested(nested(response(22, 0), 24), 2)
            require(optional(ack, 2, 0) == 0L) { "设备拒绝上传" }
            if (ack.any { it.number == 1 }) require(MessageDigest.isEqual(P.one(ack, 1, 2).bytes,
                MessageDigest.getInstance("MD5").digest(bytes))) { "设备上传 MD5 不匹配" }
            require(optional(ack, 4, 0) == 0L) { "设备要求断点续传；本会话不自动重刷或续传" }
            val chunkSize = optional(ack, 5, 2048)
            require(chunkSize in 32..(if (version == 2) maximumV2Frame - 10 else 65528).toLong()) { "设备上传分片长度不支持" }
            val parts = XiaomiFrames.uploadParts(bytes, chunkSize.toInt())
            stage(ClassicPhase.UPLOAD); transition(State.UPLOADING)
            parts.forEachIndexed { index, part ->
                send(part, data = true)
                onProgress(Progress("上传中（尚未确认安装）", index + 1, parts.size))
            }
            stage(ClassicPhase.VERIFY); transition(State.VERIFYING)
            onProgress(Progress("等待设备安装并刷新列表", 0, 1))
            var present = false
            repeat(5) { if (!present) { delay(500); present = id in listWatchfaces() } }
            require(present) { "上传结束但设备列表未确认新表盘；结果未知，禁止自动重刷" }
            stage(ClassicPhase.ACTIVATE)
            send(P.command(4, 1, 6, P.text(2, id)))
            require(number(nested(response(4, 1), 6), 4) == 1L) { "设备未确认激活；不能报告成功" }
            var active = false
            repeat(3) { if (!active) { delay(500); active = listWatchfaces()[id] == true } }
            require(active) { "设备刷新列表未确认激活；不能报告成功" }
            transition(State.SUCCEEDED)
            onProgress(Progress("设备已确认安装并激活", 1, 1))
            InstallResult(id, version, true)
        }
    }
    private suspend fun <T> runOperation(block: suspend () -> T): T {
        check(operation.tryLock()) { "已有操作正在执行" }
        try { return withContext(Dispatchers.IO) { block() } }
        catch (e: Throwable) {
            synchronized(stateLock) {
                if (lastFailure == null) lastFailure = ClassicFailure.capture(lastPhase, e)
                lastPhase = checkNotNull(lastFailure).phase
                if (state.value != State.CLOSED) mutableState.value = State.FAILED
            }
            stop()
            throw e
        } finally { operation.unlock() }
    }
    private fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { transport.close() } finally {
            scope.cancel()
            incoming.cancel()
            crypto?.close()
            authenticated = false
        }
    }
    override fun close() { synchronized(stateLock) { mutableState.value = State.CLOSED }; stop() }
}
