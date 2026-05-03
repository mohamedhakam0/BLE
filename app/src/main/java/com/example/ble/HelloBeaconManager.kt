package com.example.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Periodically broadcasts HELLO beacons with compact direct-neighbor snapshots. */
class HelloBeaconManager(
    private val context: Context,
    private val bleAdvertiser: BleAdvertiser,
    private val myNodeId: ByteArray
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        AppLogger.i("HELLO", "HelloBeaconManager.start(): first HELLO scheduled immediately")

        job = scope.launch {
            while (isActive) {
                val payload = HelloPayloadCodec.encodeDirectNeighbors(NeighborTable.getDirectNeighbors())
                val packet = MeshPacket(
                    type = PacketType.HELLO,
                    msgId = Random.nextBytes(8),
                    senderId = myNodeId,
                    receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                    ttl = 1.toByte(),
                    hopCount = 0.toByte(),
                    timestamp = (System.currentTimeMillis() / 1000L).toInt(),
                    payloadLen = payload.size.toByte(),
                    authTag = ByteArray(16),
                    payload = payload
                )

                bleAdvertiser.enqueue(packet)

                // Cut the HELLO burst after ~300ms by preempting this msgId.
                delay(300L)
                bleAdvertiser.preemptHello(packet.msgId)

                val jitter = Random.nextLong(-2_000L, 2_001L)
                val nextDelay = (10_000L + jitter).coerceAtLeast(1_000L)
                AppLogger.log(
                    "HELLO",
                    "HELLO_TX app=${context.packageName} entries=${(payload.firstOrNull()?.toInt() ?: 0) and 0xFF} nextDelayMs=$nextDelay"
                )
                delay(nextDelay)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

