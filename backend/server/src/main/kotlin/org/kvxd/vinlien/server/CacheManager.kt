package org.kvxd.vinlien.server

import org.kvxd.vinlien.shared.models.media.SearchResponse
import org.kvxd.vinlien.shared.models.media.Track

object CacheManager {
    val search = TtlCache<String, SearchResponse>(ttlMs = 30 * 60 * 1000L, maxSize = 500)
    val trending = TtlCache<String, List<Track>>(ttlMs = 30 * 60 * 1000L)
    val artwork = TtlCache<String, Pair<ByteArray, String>>(ttlMs = 24 * 60 * 60 * 1000L, maxSize = 1000)

    fun clearAll() {
        search.clear()
        trending.clear()
        artwork.clear()
    }
}
