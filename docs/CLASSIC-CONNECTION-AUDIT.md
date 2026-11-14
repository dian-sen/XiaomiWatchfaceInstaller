# Classic connection audit (offline, fixed official source)

Reference: official `https://codeberg.org/Freeyourgadget/Gadgetbridge.git`, commit
`221ae4eb08423e113968145f7b93ec8303280295`. Verified local origin and HEAD;
read the immutable Git blobs, not the moving default branch. Companion
`CLASSIC-CONNECTION-SOURCES.sha256` pins the five source files, including the
base classes used by Xiaomi. Full upstream license and authors: CLASSIC-NOTICE.md.
Raw source URL format:
`https://codeberg.org/Freeyourgadget/Gadgetbridge/raw/commit/221ae4eb08423e113968145f7b93ec8303280295/<path-in-manifest>`.
The web page fetch did not return a usable body; this audit uses the locally
available official Git objects, not a search snippet or third-party port.

## Confirmed connection path

- `XiaomiSppSupport.java:88–90`: `getSupportedService()` returns
  `XiaomiUuids.UUID_SERVICE_SERIAL_PORT_PROFILE`.
- `XiaomiUuids.java`: `String.format(BASE_UUID, "1101")`.
  `AbstractBTLEDeviceSupport.java` defines BASE_UUID as
  `0000%s-0000-1000-8000-00805f9b34fb`. Therefore the actual full UUID is
  **00001101-0000-1000-8000-00805f9b34fb**, the standard SPP service.
- `XiaomiSppSupport` does not override `getRfcommChannel()`.
  `AbstractBTBRDeviceSupport.java:271–276` defaults to
  `RFCOMM_CHANNEL_UNSPECIFIED` (negative), i.e. resolve via service UUID/SDP.
- `BtBRQueue.java:319–342` cancels existing discovery and creates a
  **secure** `createRfcommSocketToServiceRecord(mService)` when the channel is
  unspecified. Its separate reflection branch is only for an explicitly
  configured channel; it is not the Xiaomi path and is not a generic retry.
  No insecure socket or automatic channel-1 fallback belongs to this path.

**No UUID or secure/insecure mismatch was found.** Keep the existing secure
standard-SPP socket. A generic IOException alone does not prove a wrong UUID,
wrong key, unsupported SPP, or the exact remote cause. It can happen before
protocol version or any authentication exchange. Screenshot showing that error
and cleared/disabled key controls reflects a real attempted connection followed
by secret cleanup, not evidence that the user forgot to select a key.

This app deliberately requires BOND_BONDED, never pairs/discovers/reconnects,
and has no upstream auto-connect, fixed-channel, insecure or version-timeout
fallback. Before explicit connection it now cancels existing local adapter
discovery (which may have been started by another app); it never starts discovery.
Android 12+ requires both CONNECT and SCAN, requested together by the UI. SCAN is
used only to query/stop discovery in this Classic flow, not for a scan. Refusing
either permission stops connection; no silently skipped cancellation. Android's secure service-record socket may perform SDP internally
when the user explicitly connects; that is distinct from active discovery and
from merely inspecting the local UUID cache. No Bluetooth call was executed
as part of this audit, test or compilation.

## Diagnostics and privacy

`ClassicPhase` splits bond checks, socket connect, version write/read, session
configuration, nonce, proof, encrypted-list confirmation, prepare, upload,
verification and activation. Android connect errors retain their original
Throwable cause but export only a bounded sanitized report. Session captures
`failure` synchronously in its own exception handler **before FAILED**, without
relying on StateFlow observer scheduling. `phase` and `failure` survive CLOSED.
A caller still receives the original exception (including timeout/cancellation
semantics); no retries are added.

The UI shows the selected bonded-device name/type and `getUuids()` cache only
when permission already exists. It never invokes `fetchUuidsWithSdp`, discovery,
bond creation, or socket connect for this display. A missing/empty SPP entry is
labelled **no cached SPP evidence**, not proof of incompatibility. The cache is
not presented as a fresh advertisement capture.

“复制脱敏诊断” exports fixed service/socket metadata, selected bond/type/SPP-cache
status and the safe cause chain, not the key, full MAC, name, BIN data, paths,
stack traces or arbitrary parser/crypto exception text. System IO messages
are sanitized (MAC, long/space-separated hex and labelled secrets; controls
flattened), capped at 240 characters per cause and five causes, with cycle
protection. Oversized messages are omitted. Only sanitized fields persist in
the report; raw exceptions are neither logged nor copied by the UI.

## Offline validation

New fake-transport tests exercise SOCKET_CONNECT/BONDED_CHECK, VERSION_WRITE,
VERSION_READ, AUTH_NONCE, AUTH_PROOF and LIST_CONFIRM failure snapshots without
any observer, retaining them through close. Additional tests pin UUID/secure
policy, IO-message retention, MAC/key/hex sanitization, bounds and cyclic
causes. Existing synthetic V1/V2 auth/upload/activation and safety tests remain.
No watch connection, installation, APK install, radio discovery or hardware
interoperability claim is made.


## Local discovery cancellation hardening (offline only)

The user reports Band 9 Pro firmware **3.1.190**, including `SOCKET_CONNECT`
`IOException` / `IO read ret -1` after force-stopping Mi Fitness. This is a
reported observation, not proof of a firmware transport type or root cause.
Force-stopping that app is not evidence that the phone adapter's discovery is
idle. This change does **not** claim the connection has been fixed.

- After enabled/bond checks, `DISCOVERY_CANCEL` checks required permissions and
  reads `isDiscovering`. If false, no `cancelDiscovery()` call is needed.
- If true, cancel once. A false return is an explicit I/O failure at
  `DISCOVERY_CANCEL` even if the subsequent state reads false. No socket is
  created. Security exceptions also fail closed.
- A true cancellation return is not completion: poll the state at up to 50 ms
  intervals, bounded to **1500 ms** (<2 seconds) of scheduled waiting. Remaining
  true produces a timeout at `DISCOVERY_CANCEL`. Platform calls / thread
  scheduling are not a hard real-time guarantee; the overall session timeout
  still closes/interrupts the transport. No automatic cancellation retry exists.
- Only then create the original secure standard-SPP UUID/SDP socket and call
  connect once. No reflected channel 1, insecure socket, retry/reconnect, or
  firmware-based transport selection is introduced.
- SDK 26–30: existing manifest BLUETOOTH + BLUETOOTH_ADMIN; no runtime location
  prompt is needed just to stop discovery. SDK 31+: CONNECT **and** SCAN required
  even if discovery is idle, since reading its state requires SCAN too. The
  separate explicitly started BLE diagnostic scan retains its own permission
  flow and is not invoked by Classic authentication.

Transport state is an immutable `ClassicTransportSnapshot`, stored and read
under the same monitor lock as socket ownership. It includes only nullable
boolean `discoveryBefore` / `discoveryAfter` and numeric `socketElapsedMs`;
null means not reached/observed, not false/zero. Timing uses monotonic elapsed
realtime and includes a failed socket connect. Active connect time is available
on session timeout too. Captured failure snapshots survive close and do not
change with subsequent observations. These extra fields include no MAC/key.
The existing bounded redacted cause chain is retained. EOF, typed socket/session
timeout, and generic IOException are distinguished; text saying "might closed
or timeout" or "read -1" alone is **not** relabelled as a proven timeout or EOF.

The UI adds optional user-entered firmware, default **unknown**. For diagnosis
only it accepts version-shaped text such as `3.1.190`; arbitrary non-version
text is omitted from exported diagnostics to avoid identifiers/secrets. It is
not a verified profile and never chooses a transport, channel, UUID or security
mode. It is captured for each actual connection attempt. ID editing, private
copies, explicit auth, install confirmation, key wiping and failure cleanup
remain unchanged.

Offline regression coverage adds idle discovery/no-cancel, true cancellation
with delayed idle, false cancellation with both possible final states, stuck
true/1500 ms timeout, permission exception, SDK 26/30/31+ permission combinations,
immutable diagnostic capture, generic IO vs EOF vs timeout, session close/key
wipe and wiring checks. Static wiring checks are not Android instrumentation.
No watch connection, device installation, radio test or firmware compatibility
verification was performed.

Android API references (primary documentation):
- `https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#cancelDiscovery()`
- `https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isDiscovering()`
- `https://developer.android.com/develop/connectivity/bluetooth/bt-permissions`

Validation for this change: the complete local JVM source/test suite passed
**175 tests** using the cached Kotlin 2.0.21 compiler and JUnit 4.13.2.
Android `:app:compileDebugKotlin` also passed with offline Gradle 8.9.
This ARM64 validation environment cannot execute the cached x86-64 AAPT2;
therefore the Kotlin-only run excluded `:app:processDebugResources` and reused
existing generated resource symbols. Its external init script substitutes the
local same-version Bouncy Castle 1.80 JAR for the cached dependency transform.
These are environment-only workarounds, not repository dependency/build changes;
this is not a claim of a fresh APK build or install. No version, README, release
workflow, signing configuration or publication was changed.
