package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

interface MetadataProvider {
    val name: String

    val searchable: Boolean get() = true

    suspend fun search(query: String): List<Track>
    suspend fun getRecommendations(track: Track): List<Track>
    suspend fun getTrending(): List<Track>
    suspend fun getArtistInfo(name: String): ArtistInfo? = null

    suspend fun searchAlbums(query: String): List<Album> = emptyList()
    suspend fun getArtistAlbums(artist: String): List<Album> = emptyList()
    suspend fun getAlbum(id: String): Album? = null
}

interface AudioProvider {
    val name: String

    suspend fun getStreamUrl(track: Track): String

    suspend fun searchAudio(query: String): List<Track> = emptyList()
}