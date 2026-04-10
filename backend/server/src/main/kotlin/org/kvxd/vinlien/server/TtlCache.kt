package org.kvxd.vinlien.server

import java.util.concurrent.ConcurrentHashMap

class TtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxSize: Int = Int.MAX_VALUE
) {
    private val entries = ConcurrentHashMap<K, Pair<Long, V>>()

    fun get(key: K): V? {
        val (timestamp, value) = entries[key] ?: return null
        if (System.currentTimeMillis() - timestamp > ttlMs) {
            entries.remove(key)
            return null
        }
        return value
    }

    fun put(key: K, value: V) {
        if (entries.size >= maxSize) entries.clear()
        entries[key] = System.currentTimeMillis() to value
    }

    fun clear() = entries.clear()
}
