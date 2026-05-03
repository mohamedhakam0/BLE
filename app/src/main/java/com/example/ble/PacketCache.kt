package com.example.ble

import java.util.LinkedHashMap

class PacketCache {
    private val lock = Any()
    private val cache = object : LinkedHashMap<Long, Long>(500, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Long>): Boolean {
            return size > 500
        }
    }

    fun isDuplicate(msgId: Long): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val firstSeen = cache[msgId]
            if (firstSeen != null) {
                if (now - firstSeen < 60_000L) return true
                cache.remove(msgId)
            }
            cache[msgId] = now
            return false
        }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }
}

