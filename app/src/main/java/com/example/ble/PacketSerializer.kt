package com.example.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialization layer for mesh packet transport bytes.
 *
 * Wire format (Big-Endian) — matches ESP32 gateway firmware exactly:
 *  Offset  Len  Field
 *  ──────  ───  ─────────────────────────────────────────
 *       0    1  version     (Byte)
 *       1    1  type        (Byte)  -> PacketType.value
 *       2    8  msgId       (8 bytes)
 *      10    4  senderId    (4 bytes)
 *      14    4  receiverId  (4 bytes)
 *      18    1  ttl         (Byte)
 *      19    1  hopCount    (Byte)
 *      20    4  timestamp   (Int / 4 bytes, Unix seconds)
 *      24    1  flags       (Byte)  bit0=LORA_ELIGIBLE, bit3=IS_GATEWAY
 *      25    1  payloadLen  (Byte, 0-208)
 *      26   16  authTag     (16 bytes)
 *      42    *  payload     (0-208 bytes)
 */
object PacketSerializer {

    private const val MAX_PACKET_SIZE = 250
    const val FIXED_HEADER_SIZE = 42
    const val DEFAULT_INITIAL_TTL: Byte = 6

    fun serialize(packet: MeshPacket): ByteArray {
        val totalSize = FIXED_HEADER_SIZE + packet.payload.size
        require(totalSize <= MAX_PACKET_SIZE) {
            "Packet exceeds max size: $totalSize > $MAX_PACKET_SIZE"
        }

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.put(packet.version)
        buf.put(packet.type.value)
        buf.put(packet.msgId)
        buf.put(packet.senderId)
        buf.put(packet.receiverId)
        buf.put(packet.ttl)
        buf.put(packet.hopCount)
        buf.putInt(packet.timestamp)
        buf.put(packet.flags)
        buf.put(packet.payloadLen)
        buf.put(packet.authTag)
        buf.put(packet.payload)
        return buf.array()
    }

    fun deserialize(bytes: ByteArray): MeshPacket? {
        if (bytes.size < FIXED_HEADER_SIZE) {
            Log.v("BLE", "PacketSerializer: too short (${bytes.size}B) - not our packet")
            return null
        }
        if (bytes.size > MAX_PACKET_SIZE) {
            Log.v("BLE", "PacketSerializer: too large (${bytes.size}B) - dropping")
            return null
        }

        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buf.get()
            val typeValue = buf.get()
            val type = PacketType.fromValue(typeValue) ?: return null

            val msgId = ByteArray(8).also { buf.get(it) }
            val senderId = ByteArray(4).also { buf.get(it) }
            val receiverId = ByteArray(4).also { buf.get(it) }
            val ttl = buf.get()
            val hopCount = buf.get()
            val timestamp = buf.int
            val flags = buf.get()
            val payloadLen = buf.get()
            val authTag = ByteArray(16).also { buf.get(it) }

            val payloadLenInt = payloadLen.toInt() and 0xFF
            if (buf.remaining() != payloadLenInt) {
                Log.v(
                    "BLE",
                    "PacketSerializer: payloadLen mismatch len=${bytes.size} payloadLen=$payloadLenInt remaining=${buf.remaining()} - not our packet"
                )
                return null
            }

            val payload = ByteArray(payloadLenInt).also { buf.get(it) }

            MeshPacket(
                version = version,
                type = type,
                msgId = msgId,
                senderId = senderId,
                receiverId = receiverId,
                ttl = ttl,
                hopCount = hopCount,
                timestamp = timestamp,
                flags = flags,
                payloadLen = payloadLen,
                authTag = authTag,
                payload = payload
            )
        } catch (e: Exception) {
            null
        }
    }

    fun buildLeave(localSenderId: ByteArray): ByteArray {
        val packet = MeshPacket(
            type = PacketType.LEAVE,
            msgId = kotlin.random.Random.nextBytes(8),
            senderId = localSenderId,
            receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ttl = 3,
            hopCount = 0,
            timestamp = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = 0,
            authTag = ByteArray(16),
            payload = byteArrayOf()
        )
        return serialize(packet)
    }
}
