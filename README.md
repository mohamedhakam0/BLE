# Peer Reach — Offline BLE Mesh Chat

An Android app for off-grid, end-to-end encrypted messaging over Bluetooth Low Energy. Devices form a connectionless mesh: every message is broadcast via BLE Extended Advertising and relayed hop-by-hop by nearby devices until it reaches its recipient. No internet, no server, no pairing.

## Features

- **Connectionless mesh** — no GATT connections for messaging; packets travel as advertising frames, relayed with a TTL/hop-count scheme and deduplicated at every node.
- **Long range** — BLE 5 Extended Advertising on LE Coded PHY (S=8) where hardware supports it (~4× the range of 1M PHY).
- **End-to-end encryption** — X25519 key agreement + HKDF-SHA256 session keys, AES-128-GCM per message with the packet header authenticated as AAD.
- **Trust workflow** — contacts are exchanged via QR code and verified out-of-band with key fingerprints before chatting.
- **Delivery tracking** — per-message ACKs carrying hop count and round-trip time, shown as checkmarks and a "show path" detail.
- **Rich chat** — emoji reactions, starred messages, delete-for-me and delete-for-everyone (tombstones suppress late-arriving copies).
- **Avatars** — profile pictures are fetched once over a minimal GATT service during onboarding, then cached locally.
- **Always-on** — a foreground service keeps the mesh alive in the background and restarts on boot; an in-app log viewer aids field debugging.

## Protocol

Every frame is a `MeshPacket`: a 41-byte fixed header plus payload, **250 bytes max**, tagged with the mesh service UUID `12E61727-B41A-45D9-A60F-7C3B4E1D9F2A`.

| Type | Value | Purpose |
|------|-------|---------|
| HELLO | 0x01 | Neighbor presence beacon |
| CHAT | 0x02 | Encrypted message |
| ACK | 0x03 | Delivery confirmation |
| LEAVE | 0x04 | Node departure |
| REACTION | 0x05 | Emoji reaction |
| DELETE | 0x06 | Delete-for-everyone |

The header carries message id, sender/receiver ids, TTL, hop count, timestamp, and the AES-GCM auth tag. Avatar transfer is the one GATT use: service `0000FD13-…`, read-only characteristic `0000FD14-…`.

## Requirements

- **Min SDK**: 24 (extended advertising activates on API 26+ capable hardware; graceful fallback otherwise)
- **Target SDK**: 36
- At least two BLE-capable Android devices; LE Coded PHY support recommended for full range.

## How to Use

1. Install the app on two or more devices and grant the Bluetooth/notification permissions it requests.
2. On the **Keys** tab, show your QR code on one device and scan it from the other (repeat in both directions).
3. Verify the key fingerprint with your contact, then confirm to add them.
4. Chat from the **Messages** tab. Devices in between relay packets automatically — sender and recipient don't need to be in direct radio range.

## Development

```
.\gradlew.bat :app:assembleDebug          # build debug APK
.\gradlew.bat :app:testDebugUnitTest      # JVM unit tests
.\gradlew.bat :app:lintDebug              # Android lint
```

Key source files (all under `app/src/main/java/com/example/ble`):

- `MeshPacket.kt` / `PacketSerializer.kt` — wire format
- `BleAdvertiser.kt` / `BleScanner.kt` — advertising transport
- `ForegroundMeshService.kt` — mesh runtime: relay, dedup, ACKs
- `CryptoManager.kt` / `NodeIdentity.kt` — encryption and identity
- `ContactRepository.kt` / `MessageRepository.kt` — Room persistence (`peerreach.db`)
- `ChatViewModel.kt` + `ui/` — Compose UI

Debug builds include a BLE stress-test screen (`app/src/debug`). See `CLAUDE.md` for deeper architecture notes.
