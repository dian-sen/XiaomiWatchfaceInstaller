# Experimental Xiaomi SPP / Classic implementation

## License and pinned primary sources

The `classic/` implementation is an adaptation of Gadgetbridge, Copyright (C) 2023–2024 Andreas Shimokawa, José Rebelo, Yoran Vulker, licensed **AGPL-3.0-or-later**. Original notices are retained in `classic/ProtocolLicense.txt`; the full license is the repository `LICENSE` (also retained at `ble/AGPL-3.0.txt`). No upstream logging of key/session secrets was copied.

Official source repository: https://codeberg.org/Freeyourgadget/Gadgetbridge.git

Fixed revision: `221ae4eb08423e113968145f7b93ec8303280295`. The existing source checkout outside this app, `/workspace/gb-ble-research`, has this HEAD and the official Codeberg origin. The source was read locally, including both SPP versions, not inferred from BLE. The companion `CLASSIC-SOURCES.sha256` records the exact source-file digests; verify from a checkout at this revision with `sha256sum -c /path/to/CLASSIC-SOURCES.sha256`.

Relevant files at that revision:

- `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9ProCoordinator.java`: default `BT_CLASSIC`. This is not proof every firmware uses the same version.
- `service/devices/xiaomi/XiaomiUuids.java` (under the same Java package root): fixed SPP UUID `00001101-0000-1000-8000-00805f9b34fb`.
- `XiaomiSppSupport.java`: version request always uses V1 framing; select V1/V2 after response. **This app deliberately does not imitate upstream's version-timeout fallback**; absent/unknown version fails closed.
- `XiaomiSppPacketV1.java`, `XiaomiSppProtocolV1.java`: V1 stream framing, per-command encryption counter, upload channel with nonce counter zero.
- `XiaomiSppPacketV2.java`, `XiaomiSppProtocolV2.java`, `XiaomiSppV2SendWindow.java`: V2 CRC16/ARC, session config, ACK/NACK sequence semantics and go-back-N retransmission.
- `XiaomiAuthService.java`: nonce/HMAC key derivation, CCM proof/device-info and V1 traffic, V2 AES-CTR with direction key as IV.
- `services/XiaomiWatchfaceService.java`, `services/XiaomiDataUploadService.java`: prepare, MD5/length, envelope CRC32, upload chunk headers, set active and refresh.
- `GBProtobufGenerator/src/main/proto/xiaomi.proto`, `xiaomi/wear_account.proto`, `xiaomi/wear_watch_face.proto`, `xiaomi/wear_mass.proto`: exact field numbers and response definitions.

The fixed revision can be viewed at:
https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295

## Implemented wire profiles

Both explicitly negotiated **SPP V1 and V2** have non-placeholder authentication/install paths. Unknown versions fail; no BLE, alternate UUID or insecure-socket fallback is attempted. Android uses a secure RFCOMM socket for an already-bonded MAC, not GATT. No scanning, explicit bonding, automatic reconnect or automatic second install attempt.

V1 frames: `BA DC FE`, channel, flags, LE16 payload-header+payload length, opcode, 8-bit frame serial, data type, payload, `EF`. Phone protobuf commands use incrementing LE16 encryption counters starting at 1; nonce is four-byte direction prefix + LE32(0) + LE32(counter), with 32-bit CCM tag. Device traffic and phone upload use counter zero per the **SPP** source. This shared CCM primitive was verified from `SppPacketV1` and `AuthService`; it was not assumed because an existing helper happened to live in `ble/`. No BLE start/chunk/end frames or BLE ACKs are used.

V2 frames: `A5 A5`, type, serial, LE16 length, LE16 CRC16/ARC(payload), payload. Session config starts before HMAC auth. Command traffic after auth uses AES-CTR with IV=direction key as upstream specifies, not CCM or BLE nonce; bulk upload is plaintext as specified. Auth proof still uses CCM for the encrypted phone information. CRC16 is not a cryptographic integrity check; V2 inherits this weak upstream wire design and this implementation does not claim authenticated encryption for V2 command traffic.

V2 sender uses a **one-frame go-back-N window (stop-and-wait)**, within the upstream advertised capacity. ACK(seq) acknowledges that frame; NACK(expected) acknowledges frames before expected and requests resending from expected. Retries preserve the exact encoded frame/ciphertext and sequence; maximum three transmissions, default 10-second ACK deadlines. Incoming duplicates are ACKed without delivering a second command; changed duplicate contents fail; out-of-order input gets NACK(expected). Serial wraps modulo 256. No logical install or upload request is repeated after failure. Invalid length/CRC/preamble aborts safely rather than attempting unauthenticated resynchronization.

RFCOMM reads may split/coalesce arbitrarily. A bounded decoder retains incomplete frames and supports a version-switch boundary in the same read. A separate bounded read queue permits ACK deadlines to expire without cancelling the live read/socket; explicit cancellation or session close interrupts native I/O. Command, connect, and write deadlines default to 20 seconds; install has a five-minute overall deadline.

## Honest support limits

- **No physical watch/radio testing was performed.** Passing synthetic-peer tests and Android compilation does not prove interoperability with a particular 9 Pro firmware. User must opt into an experimental test.
- Pinned V1 explicitly has `TODO send response if requested by device`; there is no established V1 application ACK/retransmission encoding there. Normal V1 traffic uses reliable ordered RFCOMM without application ACK. This app **refuses a V1 data frame that sets needsResponse**, rather than fabricating a BLE ACK. This is a concrete unsupported V1 variant.
- Version 1 or 2 must be explicitly reported. Version timeout is not interpreted as version 1. Unknown future V2 session-version tuples or too-small negotiated maximum frames fail.
- No persisted/resumed upload identity: a nonzero resume position is rejected. Existing watchface ID is rejected. No overwrite/delete, firmware flashing, BLE installation, or recovery/reset operation.
- Device prepare and upload responses must contain the expected nested message; absent optional scalar fields use the proto2 defaults defined by the pinned schema (subtype/status/errno zero). No response, malformed/duplicate fields or a missing nested response is not success. Device HMAC must validate, explicit command error status must be zero, and an encrypted watchface-list round trip must succeed before READY.
- Binary validation is only the existing recognized header/ID/size validation, not proof of signature, screen-layout or model compatibility. The UI must validate and use the app-private edited working copy and show the risk before opening a connection.
- Upload byte completion and link ACKs are **never install success**. The initially absent ID must appear in a newly requested device list; a set-active response must contain ACK=1; another newly requested list must mark that exact ID active. Failure after upload is an unknown/partial outcome, not permission to reflash automatically.
- Managed-runtime zeroization is best effort, not a guarantee all runtime/JCE temporary copies can be scrubbed. Key exports are independent temporary byte arrays, not strings, logs, preferences or intent extras; consumed input and retained crypto material are erased on completion/failure/close.

## Offline reproducibility

The checked-in JUnit tests are `classic/XiaomiClassicSessionTest.kt`, plus existing `XiaomiProtocolVectorTest.kt` and `AuthKeyRecordTest.kt`. Standard Android build environment: `./gradlew :app:testDebugUnitTest` (no device required). The pure Classic session is independent of Android transport and can also be compiled/run directly with Kotlin/JVM 2.0.21, coroutines, BC 1.80, JUnit4 and org.json.

Tests cover fixed V1/V2 framing and CRC vectors, split/coalesced reads, negative lengths/CRC, known HMAC/CCM/CTR direction vectors, byte-export lifecycle, complete V1 and V2 auth→prepare→upload→MD5/size/CRC validation→fresh-list→activate→fresh-active-list flows, lost ACK/timeout/NACK exact-frame retransmission, sequence wrap, duplicated inbound frames, cancellation, unknown version, bad HMAC, existing IDs, rejected prepare, MD5 mismatch, resume rejection and rejected activation. The fake watch is synthetic and not a packet capture from hardware.

Independent CTR vectors with derived material from the existing HMAC test:

```
printf '\010\004\020\000' | openssl enc -aes-128-ctr \
  -K 923e295e02aecb7619a8e1b9f574c988 -iv 923e295e02aecb7619a8e1b9f574c988 | xxd -p
# b7e0503c
```

See `CLASSIC-API.md` for caller/permission/state/cancellation wiring. Do not label these results hardware verified.
