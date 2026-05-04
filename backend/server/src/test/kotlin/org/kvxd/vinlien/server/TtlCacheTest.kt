package org.kvxd.vinlien.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtlCacheTest {

    @Test
    fun `put and get returns value before expiry`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        cache.put("key", "value")
        assertEquals("value", cache.get("key"))
    }

    @Test
    fun `missing key returns null`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `expired entry returns null`() {
        // Use a tiny TTL that will already be expired
        val cache = TtlCache<String, String>(ttlMs = 0L)
        cache.put("key", "value")
        // Even with ttlMs=0, the entry is stored at "now"; a millisecond later it expires
        Thread.sleep(5)
        assertNull(cache.get("key"))
    }

    @Test
    fun `remove deletes an existing entry`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        cache.put("key", "value")
        cache.remove("key")
        assertNull(cache.get("key"))
    }

    @Test
    fun `clear empties all entries`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `maxSize evicts oldest entry when full`() {
        val cache = TtlCache<Int, String>(ttlMs = 60_000L, maxSize = 2)
        cache.put(1, "one")
        Thread.sleep(2) // ensure distinct timestamps
        cache.put(2, "two")
        Thread.sleep(2)
        // Adding a third entry should evict the oldest (key=1)
        cache.put(3, "three")
        assertEquals("two", cache.get(2))
        assertEquals("three", cache.get(3))
        // key=1 should have been evicted
        assertNull(cache.get(1))
    }

    @Test
    fun `overwriting a key updates the value`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        cache.put("key", "first")
        cache.put("key", "second")
        assertEquals("second", cache.get("key"))
    }
}
