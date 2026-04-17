package com.example.ble

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Periodically emits HELLO beacons and neighbor gossip for local discovery. */
class HelloBeaconManager(
    private val nodeIdentity: NodeIdentity,
    private val advertiserProvider: () -> BleAdvertiser?
) {
    companion object {
        private const val HELLO_BURST_MS = 2_000L
        private const val HELLO_BASE_INTERVAL_MS = 12_000L
        private const val HELLO_JITTER_MS = 2_000L
        private const val HELLO_BURST_STEP_MS = 350L
        private const val MAX_GOSSIP_ENTRIES = 41
    }

    private var loopJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val packetBytes = buildHelloPacketBytes()
                val burstEndsAt = SystemClock.elapsedRealtime() + HELLO_BURST_MS

                while (isActive && SystemClock.elapsedRealtime() < burstEndsAt) {
                    advertiserProvider()?.broadcast(packetBytes)
                    delay(HELLO_BURST_STEP_MS)
                }

                val interval = HELLO_BASE_INTERVAL_MS + Random.nextLong(-HELLO_JITTER_MS, HELLO_JITTER_MS)
                delay(interval.coerceAtLeast(1_000L))
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun buildHelloPacketBytes(): ByteArray {
        val identity = nodeIdentity.getOrCreateIdentity()
        val payload = buildHelloPayload(identity.senderId.toHex())
        val packet = MeshPacket(
            type = PacketType.HELLO,
            msgId = Random.nextBytes(8),
            senderId = identity.senderId,
            receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            ttl = 1,
            hopCount = 0,
            timestamp = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = payload.size.toByte(),
            authTag = ByteArray(16),
            payload = payload
        )
        return PacketSerializer.serialize(packet)
    }

    private fun buildHelloPayload(selfNodeId: String): ByteArray {
        val direct = NeighborTable.neighbors.value
            .filterIsInstance<Neighbor.Direct>()
            .asSequence()
            .filter { it.nodeId != selfNodeId }
            .take(MAX_GOSSIP_ENTRIES)
            .toList()

        if (direct.isEmpty()) return ByteArray(0)

        val payload = ByteArray(direct.size * 5)
        var offset = 0
        for (n in direct) {
            val nodeIdBytes = n.nodeId.hexToBytesOrNull(expectedBytes = 4) ?: continue
            System.arraycopy(nodeIdBytes, 0, payload, offset, 4)
            payload[offset + 4] = n.rssi.toByte()
            offset += 5
        }
        return if (offset == payload.size) payload else payload.copyOf(offset)
    }
}

private fun String.hexToBytesOrNull(expectedBytes: Int): ByteArray? {
    if (length != expectedBytes * 2) return null
    val out = ByteArray(expectedBytes)
    for (i in 0 until expectedBytes) {
        val hi = this[i * 2].digitToIntOrNull(16) ?: return null
        val lo = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

