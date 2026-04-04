/**
 * Unit tests validating MeshPacket serialization/deserialization behavior.
 */
package com.example.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random
import kotlin.text.toByte

/** Coverage tests for round-trip, bounds checks, and invalid-input handling. */
class PacketSerializerTest {

    private fun randomBytes(size: Int) = ByteArray(size).also { Random.nextBytes(it) }

    /** Verifies full field-by-field round-trip integrity. */
    @Test
    fun serializeDeserializeRoundTrip() {
        val payload = randomBytes(20)
        val packet = MeshPacket(
            version = 0x01.toByte(),
            type = PacketType.CHAT,
            msgId = randomBytes(8),
            senderId = randomBytes(4),
            receiverId = randomBytes(4),
            ttl = 2.toByte(),
            hopCount = 1.toByte(),
            timestamp = 1_700_000_000.toInt(),
            payloadLen = payload.size.toByte(),
            authTag = randomBytes(16),
            payload = payload
        )

        val bytes = PacketSerializer.serialize(packet)
        val decoded = PacketSerializer.deserialize(bytes)

        assertNotNull(decoded)
        decoded!!
        assertEquals(packet.version, decoded.version)
        assertEquals(packet.type, decoded.type)
        assertArrayEquals(packet.msgId, decoded.msgId)
        assertArrayEquals(packet.senderId, decoded.senderId)
        assertArrayEquals(packet.receiverId, decoded.receiverId)
        assertEquals(packet.ttl, decoded.ttl)
        assertEquals(packet.hopCount, decoded.hopCount)
        assertEquals(packet.timestamp, decoded.timestamp)
        assertEquals(packet.payloadLen, decoded.payloadLen)
        assertArrayEquals(packet.authTag, decoded.authTag)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    /** Verifies payload size guard rejects packets above protocol limit. */
    @Test
    fun failsWhenPayloadTooLarge() {
        val payload = randomBytes(210)

        var threw = false
        try {
            MeshPacket(
                version = 0x01.toByte(),
                type = PacketType.CHAT,
                msgId = randomBytes(8),
                senderId = randomBytes(4),
                receiverId = randomBytes(4),
                ttl = 2.toByte(),
                hopCount = 1.toByte(),
                timestamp = 1_700_000_000.toInt(),
                payloadLen = payload.size.toByte(),
                authTag = randomBytes(16),
                payload = payload
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }

        assert(threw)
    }

    /** Prints a deterministic HELLO packet in hex for manual debugging. */
    @Test
    fun printHelloPacketHex() {
        val payload = "HELLO".toByteArray(Charsets.UTF_8)
        val packet = MeshPacket(
            type = PacketType.HELLO,
            msgId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            senderId = byteArrayOf(0x11, 0x22, 0x33, 0x44),
            receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ttl = 5.toByte(),
            hopCount = 0.toByte(),
            timestamp = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = payload.size.toByte(),
            authTag = ByteArray(16) { 0x0A },
            payload = payload
        )

        val bytes = PacketSerializer.serialize(packet)
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        println("HELLO packet (${bytes.size} bytes): $hex")
    }

    /** Verifies invalid/truncated byte arrays deserialize to null. */
    @Test
    fun deserializeInvalidReturnsNull() {
        val invalidBytes = ByteArray(10)
        val result = PacketSerializer.deserialize(invalidBytes)
        assertEquals(null, result)
    }
}
