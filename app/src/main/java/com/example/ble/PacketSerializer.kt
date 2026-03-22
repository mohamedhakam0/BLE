package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format (Big-Endian):
 *
 *  Offset  Len  Field
 *  ──────  ───  ─────────────────────────────────────────
 *       0    1  version     (Byte)
 *       1    1  type        (Byte)  → PacketType.value
 *       2    8  msgId       (8 bytes)
 *      10    4  senderId    (4 bytes)
 *      14    4  receiverId  (4 bytes)
 *      18    1  ttl         (Byte)
 *      19    1  hopCount    (Byte)
 *      20    4  timestamp   (Int / 4 bytes, Unix seconds)
 *      24    1  payloadLen  (Byte, 0–209)
 *      25   16  authTag     (16 bytes)
 *      41    *  payload     (0–209 bytes)
 *
 * Total range: 41–250 bytes.
 */
object PacketSerializer {

    private const val MAX_PACKET_SIZE = 251
    const val FIXED_HEADER_SIZE       = 1 + 1 + 8 + 4 + 4 + 1 + 1 + 4 + 1 + 16  // = 41

    private const val DIAG_TAG = "BLE"

    // ── Serialise ──────────────────────────────────────────────────────────────

    fun serialize(packet: MeshPacket): ByteArray {
        val totalSize = FIXED_HEADER_SIZE + packet.payload.size
        require(totalSize <= MAX_PACKET_SIZE) {
            "Packet exceeds max size: $totalSize > $MAX_PACKET_SIZE"
        }

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.put(packet.version)
        buf.put(packet.type.value)
        buf.put(packet.msgId)           // 8 bytes
        buf.put(packet.senderId)        // 4 bytes
        buf.put(packet.receiverId)      // 4 bytes
        buf.put(packet.ttl)             // Byte
        buf.put(packet.hopCount)        // Byte
        buf.putInt(packet.timestamp)
        buf.put(packet.payloadLen)      // Byte
        buf.put(packet.authTag)         // 16 bytes
        buf.put(packet.payload)
        return buf.array()
    }

    // ── Deserialise ────────────────────────────────────────────────────────────

    fun deserialize(bytes: ByteArray): MeshPacket? {
        if (bytes.size !in FIXED_HEADER_SIZE until MAX_PACKET_SIZE) {
            diagLog("PacketSerializer.deserialize(): invalid size len=${bytes.size} -> null")
            return null
        }

        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version    = buf.get()
            val typeValue  = buf.get()
            val type       = PacketType.fromValue(typeValue) ?: run {
                diagLog("PacketSerializer.deserialize(): unknown type=0x${typeValue.toInt().and(0xFF).toString(16)} -> null")
                return null
            }

            val msgId      = ByteArray(8).also { buf.get(it) }
            val senderId   = ByteArray(4).also { buf.get(it) }
            val receiverId = ByteArray(4).also { buf.get(it) }
            val ttl        = buf.get()
            val hopCount   = buf.get()
            val timestamp  = buf.int
            val payloadLen = buf.get()
            val authTag    = ByteArray(16).also { buf.get(it) }

            val remaining      = buf.remaining()
            val payloadLenUInt = payloadLen.toInt().and(0xFF)

            if (remaining != payloadLenUInt) {
                diagLog(
                    "PacketSerializer.deserialize(): payloadLen mismatch " +
                            "len=${bytes.size} payloadLen=$payloadLenUInt remaining=$remaining -> null"
                )
                return null
            }

            val payload = ByteArray(payloadLenUInt).also { buf.get(it) }

            val pkt = MeshPacket(
                version    = version,
                type       = type,
                msgId      = msgId,
                senderId   = senderId,
                receiverId = receiverId,
                ttl        = ttl,
                hopCount   = hopCount,
                timestamp  = timestamp,
                payloadLen = payloadLen,
                authTag    = authTag,
                payload    = payload
            )

            diagLog(
                "PacketSerializer.deserialize(): OK len=${bytes.size} " +
                        "type=${pkt.type} sender=${pkt.senderId.toHex()} receiver=${pkt.receiverId.toHex()}"
            )
            pkt

        } catch (e: Exception) {
            diagLog("PacketSerializer.deserialize(): exception len=${bytes.size} -> null (${e.message})")
            null
        }
    }
}

// ── File-private helpers ───────────────────────────────────────────────────────

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Uses android.util.Log when available; falls back to println so JVM unit
 * tests don't crash with NoClassDefFoundError.
 */
private fun diagLog(msg: String) {
    runCatching {
        // If we're on Android runtime, use AppLogger so logs show in-app too.
        val loggerClass = Class.forName("com.example.ble.AppLogger")
        val d = loggerClass.getMethod("d", String::class.java, String::class.java)
        d.invoke(null, "BLE", msg)
    }.getOrElse {
        println("BLE: $msg")
    }
}