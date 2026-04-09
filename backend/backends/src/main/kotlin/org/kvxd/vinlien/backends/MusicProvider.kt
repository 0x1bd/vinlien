package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

enum class Capability {
    TRACK_SEARCH,
    ALBUM_SEARCH,
    ARTIST_INFO,
    ARTIST_ALBUMS,
    ALBUM_TRACKS,
    RECOMMENDATIONS,
    TRENDING,
    AUDIO_STREAM
}

interface MusicProvider {
    val id: String
    val name: String
    val capabilities: Set<Capability>
    val timeoutMs: Long get() = 5_000L

    suspend fun searchTracks(query: String): List<Track> = emptyList()
    suspend fun searchAlbums(query: String): List<Album> = emptyList()

    suspend fun getAlbum(artist: String, albumTitle: String): Album? = null

    suspend fun getArtistAlbums(artist: String): List<Album> = emptyList()
    suspend fun getArtistInfo(name: String): ArtistInfo? = null
    suspend fun getRecommendations(track: Track): List<Track> = emptyList()
    suspend fun getTrending(): List<Track> = emptyList()

    suspend fun resolveStream(track: Track): String? = null
    suspend fun searchAudio(query: String): List<Track> = emptyList()
}
