# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Peer Reach** — an offline Android BLE mesh chat app (Jetpack Compose, single module `app`). Devices exchange end-to-end-encrypted messages over BLE Extended Advertising with multi-hop relay; there is no server and no internet dependency.

Note: `README.md` describes an older central/peripheral GATT demo and is outdated — trust the code, not the README.

## Commands

Windows project; use `gradlew.bat` (PowerShell) or `./gradlew` from Git Bash.

```
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:compileDebugKotlin     # fast compile check (add --rerun-tasks to re-surface warnings from unchanged files)
.\gradlew.bat :app:testDebugUnitTest      # JVM unit tests (app/src/test)
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ble.CryptoManagerTest"   # single test class
.\gradlew.bat :app:lintDebug              # Android lint
```

Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true`, so `android.util.Log` calls no-op — no Robolectric/mocking needed for log statements.

## Architecture

Everything lives in `com.example.ble` (UI composables in `.ui`, debug-only tools in `.debug` under `app/src/debug`).

### Packet pipeline (the core)

- `MeshPacket.kt` — wire format: 41-byte fixed header + payload, **250 bytes max total**. `PacketType`: HELLO, CHAT, ACK, LEAVE, REACTION, DELETE.
- `PacketSerializer.kt` — MeshPacket ↔ ByteArray; rejects malformed/oversized frames.
- `BleAdvertiser.kt` / `BleScanner.kt` — connectionless transport via BLE 5 Extended Advertising (LE Coded PHY). All mesh frames are tagged with `BleConstants.MESH_SERVICE_UUID`.
- `ForegroundMeshService.kt` — the always-on runtime: keeps scan/advertise alive, deduplicates via `PacketCache`, relays packets (TTL/hop count), sends ACKs for inbound CHAT, and bridges packets to the UI through local broadcasts. Restarted on boot by `BootReceiver`. `HelloBeaconManager`/`NeighborTable` maintain neighbor presence from HELLO beacons.
- `ChatViewModel.kt` — receives bridged packets and applies message semantics: decrypt, persist, ACK bookkeeping (hop count/RTT), reactions, delete-for-everyone (with tombstone inserts for out-of-order arrival).

When changing anything in this path, keep the 250-byte packet budget and the no-connection (advertise-only) model in mind.

### Crypto & identity

- `NodeIdentity.kt` — per-device X25519 keypair + 4-byte senderId.
- `CryptoManager.kt` — BouncyCastle: X25519 ECDH → HKDF-SHA256 (symmetric derivation — both peers must compute the identical session key) → AES-128-GCM. The 22-byte AAD is built from the packet header, and the GCM tag travels in the header's `authTag` field.
- Contacts are exchanged via QR (`QrModels.kt`, `ui/QrGenerateScreen.kt`, `ui/QrScanScreen.kt`) and verified by fingerprint (`ui/TrustVerificationScreen.kt`, `ui/KeysScreen.kt` + `KeysViewModel`).

### Persistence (Room)

- Single DB `peerreach.db`, **`AppDatabase` is declared in `ContactRepository.kt`** (not its own file), currently schema v9 with manual `Migration` objects and `exportSchema = false`. Schema changes require a new migration in that file.
- Entities: `Contact` (in ContactRepository.kt), `MessageEntity` + `ReactionEntity` (in MessageRepository.kt).
- `ContactRepository` / `MessageRepository` are process-wide singletons (`getInstance(context)`) shared by ViewModels and the service.
- Messages use soft deletes (`deleted` / `deletedForEveryone` / tombstones); queries filter accordingly. `ackBroadcastExhausted` and `deliveryCompleted` columns are legacy — kept only for schema compatibility, never read.

### Avatars

`GattAvatarServer` (the one GATT use in the app) serves the local avatar JPEG for one-time fetch during onboarding; `GattAvatarFetcher` pulls it from the peer; `AvatarManager` stores avatars on disk keyed by node id.

### Debug-only tooling

`app/src/debug/.../debug/` contains a BLE stress-test screen/manager, referenced from main code only behind `DebugBuildFlags.isDebug` checks. Keep debug-only code in that source set.

### Logging

Use `AppLogger` (not `android.util.Log` directly) for anything user-diagnosable — it feeds the in-app log viewer (`ui/LogViewerScreen.kt`) in addition to Logcat.
