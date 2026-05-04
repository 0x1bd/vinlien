package org.kvxd.vinlien.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtlCacheTest {

    /** Returns a clock that can be manually advanced by mutating [nowMs]. */
    private fun mutableClock(initial: Long = 0L): Pair<() -> Long, (Long) -> Unit> {
        var now = initial
        return Pair({ now }, { delta -> now += delta })
    }

    @Test
    fun `put and get returns value before expiry`() {
        val (clock, _) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clockMs = clock)
        cache.put("key", "value")
        assertEquals("value", cache.get("key"))
    }

    @Test
    fun `missing key returns null`() {
        val (clock, _) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clockMs = clock)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `expired entry returns null`() {
        val (clock, advance) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 1_000L, clockMs = clock)
        cache.put("key", "value")
        advance(2_000L) // advance past TTL
        assertNull(cache.get("key"))
    }

    @Test
    fun `entry is valid just before ttl boundary`() {
        val (clock, advance) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 1_000L, clockMs = clock)
        cache.put("key", "value")
        advance(999L) // still within TTL
        assertEquals("value", cache.get("key"))
    }

    @Test
    fun `remove deletes an existing entry`() {
        val (clock, _) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clockMs = clock)
        cache.put("key", "value")
        cache.remove("key")
        assertNull(cache.get("key"))
    }

    @Test
    fun `clear empties all entries`() {
        val (clock, _) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clockMs = clock)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `maxSize evicts oldest entry when full`() {
        var now = 0L
        val clock: () -> Long = { now }
        val cache = TtlCache<Int, String>(ttlMs = 60_000L, maxSize = 2, clockMs = clock)

        now = 1L; cache.put(1, "one")
        now = 2L; cache.put(2, "two")
        now = 3L; cache.put(3, "three") // should evict key=1 (oldest)

        assertEquals("two", cache.get(2))
        assertEquals("three", cache.get(3))
        assertNull(cache.get(1))
    }

    @Test
    fun `overwriting a key updates the value`() {
        val (clock, _) = mutableClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clockMs = clock)
        cache.put("key", "first")
        cache.put("key", "second")
        assertEquals("second", cache.get("key"))
    }
}

