package com.example.ble

import java.util.Arrays

enum class PacketType(val value: Byte) {
    HELLO(0x01),
    CHAT(0x02),
    ACK(0x03);

    companion object {
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
