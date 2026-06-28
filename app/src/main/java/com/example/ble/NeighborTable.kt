package com.example.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** In-memory neighbor record used for direct (hop 0) and extended (hop 1) discovery. */
data class NeighborEntry(
    val nodeId: String,
    val rssi: Int,
    val lastSeen: Long,
    val hopCount: Int,
    val seenVia: String? = null,
    val nodeType: NodeType = NodeType.PHONE
)

/** In-memory neighbor table; direct observations always win over relayed entries. */
object NeighborTable {
    private val table = ConcurrentHashMap<String, NeighborEntry>()
    private val _neighbors = MutableStateFlow<List<NeighborEntry>>(emptyList())
    val neighbors: StateFlow<List<NeighborEntry>> = _neighbors.asStateFlow()
    @Volatile
    private var selfNodeId: String? = null

    /**
     * Ghost cache: records the last time we received a direct (hop-0) HELLO from each node.
     * Entries here outlive the active [table] so the router can make an optimistic BLE
     * attempt for peers whose HELLO recently expired rather than immediately diverting to LoRa.
     * TTL is checked lazily on [wasRecentlySeen]; no background eviction needed.
     */
    private val recentlySeen = ConcurrentHashMap<String, Long>()   // nodeId → lastSeenMs

    /** How long a direct sighting is considered "recent" for optimistic BLE routing. */
    const val RECENTLY_SEEN_TTL_MS = 90_000L  // 1 min 30 s

    val directCount: Int
        get() = table.values.count { it.hopCount == 0 }

    fun setSelfNodeId(nodeId: String) {
        selfNodeId = nodeId.trim().lowercase()
    }

    fun clear() {
        table.clear()
        recentlySeen.clear()
        emitSnapshot()
    }

    fun removeNode(nodeId: String) {
        val key = nodeId.trim().lowercase()
        val removed = table.remove(key)
        if (removed != null) {
            emitSnapshot()
        }
    }

    fun upsert(entry: NeighborEntry) {
        val key = entry.nodeId.lowercase()
        if (selfNodeId != null && key == selfNodeId) return
        val normalized = entry.copy(nodeId = key)
        val existing = table[key]

        // Every direct (hop-0) sighting refreshes the ghost cache regardless of
        // whether the active-table entry actually changes.
        if (normalized.hopCount == 0) {
            recentlySeen[key] = entry.lastSeen
        }

        if (existing != null) {
            if (existing.hopCount == 0 && normalized.hopCount == 1) {
                return
            }
            if (existing.hopCount == 1 && normalized.hopCount == 0) {
                table[key] = normalized
                emitSnapshot()
                return
            }
        }

        table[key] = normalized
        emitSnapshot()
    }

    fun upsertAll(entries: List<NeighborEntry>) {
        var changed = false
        for (entry in entries) {
            val key = entry.nodeId.lowercase()
            if (selfNodeId != null && key == selfNodeId) continue
            val normalized = entry.copy(nodeId = key)
            val existing = table[key]

            if (existing != null && existing.hopCount == 0 && normalized.hopCount == 1) {
                continue
            }
            if (existing != normalized) {
                table[key] = normalized
                changed = true
            }
        }
        if (changed) emitSnapshot()
    }

    /**
     * Returns true if a direct HELLO from [nodeId] was received within the last
     * [RECENTLY_SEEN_TTL_MS] milliseconds, even if the entry has since been evicted
     * from the active neighbor table.
     *
     * Used by the router to make an optimistic BLE send attempt instead of immediately
     * diverting to LoRa when a HELLO cycle was missed (e.g. BLE collision at 10 m).
     */
    fun wasRecentlySeen(nodeId: String): Boolean {
        val ts = recentlySeen[nodeId.trim().lowercase()] ?: return false
        return System.currentTimeMillis() - ts <= RECENTLY_SEEN_TTL_MS
    }

    fun evictStale() {
        val now = System.currentTimeMillis()
        var changed = false

        val it = table.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            val maxAge = if (entry.hopCount == 0) 45_000L else 60_000L
            if (now - entry.lastSeen > maxAge) {
                it.remove()
                changed = true
            }
        }

        if (changed) emitSnapshot()
    }

    /**
     * Returns the table entry for [nodeId], or null if the node is not currently known.
     * Both direct (hopCount=0) and extended/piggybacked (hopCount=1) entries are returned;
     * callers can inspect [NeighborEntry.hopCount] to distinguish the two cases.
     */
    fun lookup(nodeId: String): NeighborEntry? = table[nodeId.trim().lowercase()]

    fun getDirectNeighbors(): List<NeighborEntry> =
        table.values
            .asSequence()
            .filter { it.hopCount == 0 }
            .sortedByDescending { it.rssi }
            .toList()

    /** Returns true if at least one direct (hop-0) neighbor is a LoRa-capable gateway. */
    fun hasGatewayNeighbor(): Boolean =
        table.values.any { it.hopCount == 0 && it.nodeType == NodeType.GATEWAY }

    private fun emitSnapshot() {
        _neighbors.value = table.values.sortedWith(
            compareBy<NeighborEntry> { it.hopCount }
                .thenByDescending { it.rssi }
                .thenByDescending { it.lastSeen }
        )
    }
}
