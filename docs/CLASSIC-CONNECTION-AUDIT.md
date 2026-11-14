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
fallback. It does not cancel another app's discovery (no SCAN request for
Classic). Android's secure service-record socket may perform SDP internally
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
