/**
 * Example helper snippets showing how to use advertiser/scanner APIs with mesh packets.
 *
 * These functions are reference code for manual testing and integration guidance.
 */
package com.example.ble

import android.bluetooth.BluetoothAdapter
import kotlin.random.Random

/** Broadcasts a HELLO packet to all peers using BLE advertising. */
fun exampleBasicBroadcast(bluetoothAdapter: BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    val payload = "Hello, everyone!".encodeToByteArray()
    val packet = MeshPacket(
        type = PacketType.HELLO,
        msgId = Random.nextBytes(8),
        senderId = Random.nextBytes(4),
        receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        ttl = 5,
        hopCount = 0,
        timestamp = (System.currentTimeMillis() / 1000L).toInt(),
        payloadLen = payload.size.toByte(),
        authTag = Random.nextBytes(16),
        payload = payload
    )

    advertiser.broadcast(PacketSerializer.serialize(packet))
}

/** Starts scanning and prints decoded messages to stdout for diagnostics. */
fun exampleScanForMessages(context: android.content.Context, bluetoothAdapter: BluetoothAdapter?) {
    val scanner = BleScanner(context, bluetoothAdapter) { packet ->
        val payloadText = packet.payload.decodeToString()
        println("Received: '$payloadText'")
        println("  Sender: ${packet.senderId.joinToString("") { "%02x".format(it) }}")
        println("  Receiver: ${packet.receiverId.joinToString("") { "%02x".format(it) }}")
    }

    scanner.startScanning()
}

/** Broadcasts a targeted CHAT packet addressed to one receiver ID. */
fun exampleTargetedBroadcast(bluetoothAdapter: BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)
    val payload = "Message for you specifically".encodeToByteArray()

    val packet = MeshPacket(
        type = PacketType.CHAT,
        msgId = Random.nextBytes(8),
        senderId = Random.nextBytes(4),
        receiverId = Random.nextBytes(4),
        ttl = 3,
        hopCount = 0,
        timestamp = (System.currentTimeMillis() / 1000L).toInt(),
        payloadLen = payload.size.toByte(),
        authTag = Random.nextBytes(16),
        payload = payload
    )

    advertiser.broadcast(PacketSerializer.serialize(packet))
}

/** Demonstrates sending repeated status updates by rebroadcasting new packet payloads. */
fun exampleUpdateBroadcast(bluetoothAdapter: BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    fun createStatus(status: String): ByteArray {
        val payload = status.encodeToByteArray()
        val packet = MeshPacket(
            type = PacketType.CHAT,
            msgId = Random.nextBytes(8),
            senderId = Random.nextBytes(4),
            receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ttl = 5,
            hopCount = 0,
            timestamp = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = payload.size.toByte(),
            authTag = Random.nextBytes(16),
            payload = payload
        )
        return PacketSerializer.serialize(packet)
    }

    // Start first status
    advertiser.broadcast(createStatus("Status: Active"))

    // ... later ... update by broadcasting again
    advertiser.broadcast(createStatus("Status: Inactive"))
}
