/**
 * Defines the core mesh packet structure and types for Peer Reach BLE network.
 *
 * This file contains:
 * - PacketType enum: Identifies packet purpose (HELLO, CHAT, ACK)
 * - MeshPacket data class: The complete 42-byte fixed header packet format
 *
 * Main Components:
 * - PacketType: HELLO (0x01) for peer discovery, CHAT (0x02) for messages, ACK (0x03) for confirmations
 * - MeshPacket: 42-byte fixed header + variable payload (max 250 bytes total)
 *   - version (1 byte): Protocol version (currently 0x01)
 *   - type (1 byte): PacketType enum value
 *   - msgId (8 bytes): Unique message identifier for deduplication
 *   - senderId (4 bytes): Source node ID (derived from public key SHA-256)
 *   - receiverId (4 bytes): Destination node ID (0xFFFFFFFF for broadcast)
 *   - ttl (1 byte): Time to live, decremented at each hop
 *   - hopCount (1 byte): Number of hops traveled
 *   - timestamp (4 bytes): Unix seconds when packet was created
 *   - payloadLen (1 byte): Length of payload (0-209 bytes)
 *   - authTag (16 bytes): Authentication/encryption tag
 *   - payload (0-209 bytes): Message content
 *
 * Interactions:
 * - PacketSerializer.kt: Serializes MeshPacket to ByteArray, deserializes ByteArray to MeshPacket
 * - BleAdvertiser.kt: Broadcasts serialized packets via BLE 5.0 Extended Advertising
 * - BleScanner.kt: Receives advertising packets, passes to PacketSerializer for deserialization
 * - ForegroundMeshService.kt: Receives deserialized packets, routes to ChatViewModel or sends ACKs
 * - ChatViewModel.kt: Creates CHAT packets for user messages, receives ACKs
 * - ESP32 Bridge: Same packet structure used for device-to-device communication
 *
 * Thread Safety: Data class is immutable, safe for concurrent reads across all threads
 * Storage: Max 250 bytes fits in BLE 5.0 extended advertising payload (251 bytes service data limit)
 * Validation: init block enforces strict byte size requirements to prevent serialization errors
 */
package com.example.ble

import java.util.Arrays

enum class PacketType(val value: Byte) {
    HELLO(0x01),
    CHAT(0x02),
    ACK(0x03),
    LEAVE(0x04);

    companion object {
        const val TYPE_HELLO: Byte = 0x01
        const val TYPE_CHAT: Byte = 0x02
        const val TYPE_ACK: Byte = 0x03
        const val TYPE_LEAVE: Byte = 0x04

        fun fromValue(value: Byte): PacketType? = entries.firstOrNull { it.value == value }
    }
}

data class MeshPacket(
    val version: Byte = 0x01,
    val type: PacketType,
    val msgId: ByteArray,
    val senderId: ByteArray,
    val receiverId: ByteArray,
    val ttl: Byte,
    val hopCount: Byte,
    val timestamp: Int,
    val payloadLen: Byte,
    val authTag: ByteArray,
    val payload: ByteArray
) {
    init {
        require(msgId.size == 8) { "msgId must be 8 bytes" }
        require(senderId.size == 4) { "senderId must be 4 bytes" }
        require(receiverId.size == 4) { "receiverId must be 4 bytes" }
        require(authTag.size == 16) { "authTag must be 16 bytes" }
        require(payload.size == payloadLen.toInt()) { "payload length mismatch" }
        require(payload.size <= 209) { "payload must be <= 209 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return version == other.version &&
            type == other.type &&
            msgId.contentEquals(other.msgId) &&
            senderId.contentEquals(other.senderId) &&
            receiverId.contentEquals(other.receiverId) &&
            ttl == other.ttl &&
            hopCount == other.hopCount &&
            timestamp == other.timestamp &&
            payloadLen == other.payloadLen &&
            authTag.contentEquals(other.authTag) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + Arrays.hashCode(msgId)
        result = 31 * result + Arrays.hashCode(senderId)
        result = 31 * result + Arrays.hashCode(receiverId)
        result = 31 * result + ttl
        result = 31 * result + hopCount
        result = 31 * result + timestamp
        result = 31 * result + payloadLen
        result = 31 * result + Arrays.hashCode(authTag)
        result = 31 * result + Arrays.hashCode(payload)
        return result
    }
}