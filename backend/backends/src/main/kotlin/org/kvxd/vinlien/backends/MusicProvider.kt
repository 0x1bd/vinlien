package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.Track

enum class Capability {
    TRACK_SEARCH,
    ALBUM_SEARCH,
    ARTIST_INFO,
    ARTIST_ALBUMS,
    ARTIST_TOP_TRACKS,
    ALBUM_TRACKS,
    RECOMMENDATIONS,
    TRENDING,
    AUDIO_STREAM
}

sealed interface StreamResolutionResult {
    data class Success(val streamUrl: String, val providerId: String) : StreamResolutionResult
    data class Failure(val providerId: String, val reason: String, val cause: Throwable? = null) : StreamResolutionResult
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
    suspend fun getArtistTopTracks(artist: String): List<Track> = emptyList()
    suspend fun getArtistInfo(name: String): ArtistInfo? = null
    suspend fun getRecommendations(track: Track): List<Track> = emptyList()
    suspend fun getTrending(): List<Track> = emptyList()

    suspend fun resolveStream(track: Track): StreamResolutionResult =
        StreamResolutionResult.Failure(
            providerId = id,
            reason = "Provider '$id' does not support audio streaming"
        )

    suspend fun searchAudio(query: String): List<Track> = emptyList()
}
