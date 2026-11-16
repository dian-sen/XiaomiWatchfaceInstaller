package dev.xiaom.watchfaceinstaller

/** Experimental boundary for Xiaomi Band 9 Pro. No unverified packets are sent yet. */
interface XiaomiBand9ProAdapter {
    suspend fun connect(): Result<Unit>
    suspend fun authenticate(authKey: ByteArray): Result<Unit>
    suspend fun install(file: java.io.File, onProgress: (sent: Long, total: Long) -> Unit): Result<Unit>
    suspend fun disconnect()
}

class ExperimentalBand9ProAdapter : XiaomiBand9ProAdapter {
    override suspend fun connect() = Result.failure<Unit>(UnsupportedOperationException("Direct Band 9 Pro protocol is experimental"))
    override suspend fun authenticate(authKey: ByteArray) = Result.failure<Unit>(UnsupportedOperationException("Auth flow not enabled"))
    override suspend fun install(file: java.io.File, onProgress: (Long, Long) -> Unit) = Result.failure<Unit>(UnsupportedOperationException("Use Gadgetbridge external installer"))
    override suspend fun disconnect() = Unit
}
