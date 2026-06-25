package com.example.ble

/** Encodes/decodes HELLO payloads: count byte + fixed 5-byte entries (4-byte nodeId, 1-byte RSSI). */
object HelloPayloadCodec {
    private const val MAX_ENTRIES = 20
    private const val ENTRY_BYTES = 5

    fun encodeDirectNeighbors(entries: List<NeighborEntry>): ByteArray {
        val top = entries
            .asSequence()
            .filter { it.hopCount == 0 }
            .sortedByDescending { it.rssi }
            .take(MAX_ENTRIES)
            .toList()

        // Always append node-type byte (0x00 = phone) so receivers can identify us.
        if (top.isEmpty()) return byteArrayOf(0, NodeType.PHONE.wire)

        val out = ByteArray(1 + top.size * ENTRY_BYTES + 1)  // +1 for trailing nodeType
        out[0] = top.size.toByte()

        var offset = 1
        for (entry in top) {
            val nodeIdBytes = entry.nodeId.hexToByteArray4() ?: continue
            System.arraycopy(nodeIdBytes, 0, out, offset, 4)
            out[offset + 4] = entry.rssi.coerceIn(-128, 0).toByte()
            offset += ENTRY_BYTES
        }

        val actualCount = (offset - 1) / ENTRY_BYTES
        return if (actualCount == top.size) {
            out[out.size - 1] = NodeType.PHONE.wire
            out
        } else {
            // Some entries had invalid hex IDs — resize and fix count.
            val trimmed = ByteArray(offset + 1)
            System.arraycopy(out, 0, trimmed, 0, offset)
            trimmed[0] = actualCount.toByte()
            trimmed[offset] = NodeType.PHONE.wire
            trimmed
        }
    }

    fun decode(payload: ByteArray, seenVia: String, now: Long = System.currentTimeMillis()): List<NeighborEntry> {
        if (payload.isEmpty()) return emptyList()

        val count = payload[0].toInt() and 0xFF
        if (count == 0) return emptyList()
        if (count > MAX_ENTRIES) return emptyList()

        val expected = 1 + (count * ENTRY_BYTES)
        if (payload.size < expected) return emptyList()

        val entries = ArrayList<NeighborEntry>(count)
        var offset = 1
        repeat(count) {
            val nodeBytes = payload.copyOfRange(offset, offset + 4)
            val nodeId = nodeBytes.toHex()
            val rssi = payload[offset + 4].toInt()
            entries.add(
                NeighborEntry(
                    nodeId = nodeId,
                    rssi = rssi,
                    lastSeen = now,
                    hopCount = 1,
                    seenVia = seenVia
                )
            )
            offset += ENTRY_BYTES
        }
        return entries
    }
}

private fun String.hexToByteArray4(): ByteArray? {
    if (length != 8) return null
    val out = ByteArray(4)
    for (i in 0 until 4) {
        val hi = this[i * 2].digitToIntOrNull(16) ?: return null
        val lo = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }


