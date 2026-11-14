# Experimental Classic installation API

`dev.xiaom.watchfaceinstaller.classic.XiaomiClassicSession(transport: ClassicTransport, phoneApi: Int = 35, phoneModel: String = "Android", region: String = "CN")`.

Android caller: `XiaomiClassicSession(AndroidClassicTransport(context), Build.VERSION.SDK_INT, Build.MODEL, region)`.

- `suspend fun connectAndAuthenticate(mac: String, key: ByteArray)` consumes and zeros the supplied 16-byte key even on error. Use `AuthKeyRecord.copyKeyBytes()` immediately before calling. Close the record after copying. No persistent secret/logging.
- `suspend fun install(file: File, onProgress: (Progress) -> Unit = {}): InstallResult`. Caller provides a validated app-private edited working copy and explicit user consent. No automatic retry of installation. `Progress(phase: String, completed: Int, total: Int)` is emitted on the worker coroutine; dispatch UI updates appropriately. `InstallResult(id: String, protocolVersion: Int, activeConfirmed: Boolean)` only returns after a fresh device list confirms the new ID and active flag.
- `val state: StateFlow<State>`; enum `IDLE, CONNECTING, NEGOTIATING, AUTHENTICATING, READY, PREPARING, UPLOADING, VERIFYING, SUCCEEDED, FAILED, CLOSED`.
- `close()` immediately closes transport and cancels pending I/O; cancel the calling coroutine too. Session is single-use; failure never reconnects or reflashes. Use a new session for a new explicit attempt.

User-facing label must say experimental / not hardware-tested. Authentication is not install success. 100% bytes written is not success. Show unknown outcome on post-upload failure; do not suggest blindly retrying. Existing-ID replacement is deliberately refused to prevent a stale list from appearing to prove installation.

No device access occurs on construction. Connection requires the Android Bluetooth runtime permission; implementation does not request permissions or initiate explicit pairing. Only already-bonded devices are accepted. It never scans or probes alternative services. UUID: `00001101-0000-1000-8000-00805f9b34fb`.

## Profile and completion details

Both explicitly negotiated SPP V1 and V2 are implemented. V2 uses a one-packet ACK window, duplicate suppression and bounded exact-frame retries; unsupported/absent version is an error, never a silent fallback. V1 has no invented BLE ACK; the pinned upstream leaves V1 needsResponse handling undefined, and that variant is refused. See `CLASSIC-NOTICE.md` for the complete limits.

`READY` requires a verified watch HMAC and an encrypted list round trip, not just a socket. `SUCCEEDED` requires new-ID presence, explicit activation ACK=1 and a fresh active-list response. `close()` sets `CLOSED` even after success, so retain the returned result separately if the UI needs to show it after closing. Cancellation is propagated, all failed operations close the transport; no background reconnect. Constructor timeout parameters are only for tests/tuning, not permission to retry installation.

Minimal caller sequence (run inside a lifecycle-owned coroutine, catch cancellation separately):

```kotlin
val session = XiaomiClassicSession(AndroidClassicTransport(this), Build.VERSION.SDK_INT, Build.MODEL, "CN")
try {
    record.use { session.connectAndAuthenticate(it.mac, it.copyKeyBytes()) }
    val result = session.install(validatedPrivateWorkingCopy) { progress ->
        // worker thread; marshal state updates onto Main as appropriate
    }
    // Show confirmed success only here, from result.activeConfirmed.
} finally {
    session.close()
}
```

Do not construct the transport/session until the explicit user test action. Do not save the key in ViewModel/SavedState/Bundle. RootAuthKeySource is owned by the UI flow, not called by this library. Do not read the database just to render the page.
