package org.kvxd.vinlien.server

import org.kvxd.vinlien.shared.models.feed.HomeFeed
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.SearchResponse
import org.kvxd.vinlien.shared.models.media.Track

object CacheManager {
    val search = TtlCache<String, SearchResponse>(ttlMs = 30 * 60 * 1000L, maxSize = 500)
    val artistInfo = TtlCache<String, ArtistInfo>(ttlMs = 60 * 60 * 1000L, maxSize = 200)
    val artistAlbums = TtlCache<String, List<Album>>(ttlMs = 60 * 60 * 1000L, maxSize = 200)
    val artistTracks = TtlCache<String, List<Track>>(ttlMs = 30 * 60 * 1000L, maxSize = 200)
    val albumDetail = TtlCache<String, Album>(ttlMs = 60 * 60 * 1000L, maxSize = 500)
    val trending = TtlCache<String, List<Track>>(ttlMs = 30 * 60 * 1000L)
    val homeFeed = TtlCache<String, HomeFeed>(ttlMs = 5 * 60 * 1000L, maxSize = 100)
    val artwork = TtlCache<String, Pair<ByteArray, String>>(ttlMs = 24 * 60 * 60 * 1000L, maxSize = 500)
    val diskArtwork = DiskArtworkCache()

    fun clearAll() {
        search.clear()
        artistInfo.clear()
        artistAlbums.clear()
        artistTracks.clear()
        albumDetail.clear()
        trending.clear()
        homeFeed.clear()
        artwork.clear()
        diskArtwork.clear()
    }
}
