package org.kvxd.vinlien.backends.deezer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.BackendDebugger
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.fetchDebug
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder

@Serializable
private data class DzrListResponse<T>(
    val data: List<T> = emptyList(),
    val total: Int? = null
)

@Serializable
private data class DzrTrack(
    val id: Long? = null,
    val title: String? = null,
    val duration: Int? = null,
    val artist: DzrArtistRef? = null,
    val album: DzrAlbumRef? = null,
    val contributors: List<DzrArtistRef> = emptyList()
) {
    fun toDomainTrack(): Track? {
        val trackId = id ?: return null
        val title = title ?: return null
        val artistName = artist?.name ?: return null
        val artworkUrl = (album?.coverXl ?: album?.coverBig).validDeezerImage()
        val allArtists = contributors.mapNotNull { it.name }.ifEmpty { listOf(artistName) }
        return Track(
            id = "deezer:$trackId",
            title = title,
            artist = allArtists.joinToString(", "),
            artists = allArtists,
            durationMs = (duration ?: 0).toLong() * 1000L,
            artworkUrl = artworkUrl,
            canonicalId = Normalizer.canonicalIdFor(artistName, title)
        )
    }
}

@Serializable
private data class DzrArtistRef(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null
)

@Serializable
private data class DzrAlbumRef(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null
)

@Serializable
private data class DzrAlbumSummary(
    val id: Long? = null,
    val title: String? = null,
    val artist: DzrArtistRef? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("record_type") val recordType: String? = null
) {
    fun toDomainAlbum(): Album? {
        val albumId = id ?: return null
        val title = title ?: return null
        val artistName = artist?.name ?: return null
        val artworkUrl = (coverXl ?: coverBig).validDeezerImage()
        val year = releaseDate?.take(4)?.toIntOrNull()
        return Album(
            id = "deezer:album:${albumId}:::${artistName}:::${title}",
            title = title,
            artist = artistName,
            artworkUrl = artworkUrl,
            year = year
        )
    }
}

@Serializable
private data class DzrAlbumFull(
    val id: Long? = null,
    val title: String? = null,
    val artist: DzrArtistRef? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val tracks: DzrAlbumTracks? = null
)

@Serializable
private data class DzrAlbumTracks(val data: List<DzrTrack> = emptyList())

@Serializable
private data class DzrArtistFull(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("nb_fan") val nbFan: Long? = null,
    @SerialName("nb_album") val nbAlbum: Int? = null
)

private val DEEZER_PLACEHOLDER = Regex("""/images/\w+//""")

private fun String?.validDeezerImage(): String? =
    takeIf { !it.isNullOrBlank() && !DEEZER_PLACEHOLDER.containsMatchIn(it) }

class DeezerMetadataProvider : MusicProvider {
    override val id = "deezer"
    override val name = "Deezer"
    override val capabilities = setOf(
        Capability.TRACK_SEARCH,
        Capability.ALBUM_SEARCH,
        Capability.ARTIST_INFO,
        Capability.ARTIST_ALBUMS,
        Capability.ALBUM_TRACKS,
        Capability.RECOMMENDATIONS,
        Capability.TRENDING
    )

    private fun apiUrl(path: String, vararg params: Pair<String, String>): String {
        val base = "https://api.deezer.com/$path"
        if (params.isEmpty()) return base
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$base?$query"
    }

    private suspend inline fun <reified T> fetchParsed(url: String, capability: String): T {
        val body = fetchDebug(url, providerId = id, capability = capability)
        return sharedJson.decodeFromString(body)
    }

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed<DzrListResponse<DzrTrack>>(
                apiUrl("search", "q" to query, "limit" to "25"),
                "TRACK_SEARCH"
            )
            val queryTokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
            val tracks = res.data.mapNotNull { it.toDomainTrack() }
                .filter { track ->
                    if (queryTokens.isEmpty()) return@filter true
                    val haystack = "${track.title} ${track.artist}".lowercase()
                    queryTokens.any { token -> haystack.contains(token) }
                }
            BackendDebugger.logResponse(id, "TRACK_SEARCH", tracks.size, "")
            tracks
        } catch (e: Exception) {
            BackendDebugger.logError(id, "TRACK_SEARCH", e)
            emptyList()
        }
    }

    override suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed<DzrListResponse<DzrAlbumSummary>>(
                apiUrl("search/album", "q" to query, "limit" to "20"),
                "ALBUM_SEARCH"
            )
            val albums = res.data.mapNotNull { it.toDomainAlbum() }
            BackendDebugger.logResponse(id, "ALBUM_SEARCH", albums.size, "")
            albums
        } catch (e: Exception) {
            BackendDebugger.logError(id, "ALBUM_SEARCH", e)
            emptyList()
        }
    }

    override suspend fun getAlbum(artist: String, albumTitle: String): Album? = withContext(Dispatchers.IO) {
        try {
            val searchRes = fetchParsed<DzrListResponse<DzrAlbumSummary>>(
                apiUrl("search/album", "q" to "$artist $albumTitle", "limit" to "10"),
                "ALBUM_TRACKS"
            )
            val match = searchRes.data.firstOrNull { a ->
                a.title?.equals(albumTitle, ignoreCase = true) == true &&
                        a.artist?.name?.lowercase()?.contains(artist.lowercase()) == true
            } ?: searchRes.data.firstOrNull { a ->
                a.title?.contains(albumTitle, ignoreCase = true) == true
            } ?: return@withContext null

            val albumId = match.id ?: return@withContext null

            val full = fetchParsed<DzrAlbumFull>(
                apiUrl("album/$albumId"),
                "ALBUM_TRACKS"
            )

            val resolvedArtist = full.artist?.name ?: artist
            val resolvedTitle = full.title ?: albumTitle
            val artworkUrl = (full.coverXl ?: full.coverBig).validDeezerImage()
            val year = full.releaseDate?.take(4)?.toIntOrNull()

            val tracks = full.tracks?.data.orEmpty().mapNotNull { t ->
                t.toDomainTrack()?.let { track ->
                    if (track.artworkUrl == null && artworkUrl != null) {
                        track.copy(artworkUrl = artworkUrl)
                    } else track
                }
            }

            BackendDebugger.logResponse(id, "ALBUM_TRACKS", tracks.size, "")

            Album(
                id = "deezer:album:${albumId}:::${resolvedArtist}:::${resolvedTitle}",
                title = resolvedTitle,
                artist = resolvedArtist,
                artworkUrl = artworkUrl,
                year = year,
                tracks = tracks
            )
        } catch (e: Exception) {
            BackendDebugger.logError(id, "ALBUM_TRACKS", e)
            null
        }
    }

    override suspend fun getArtistAlbums(artist: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val artistId = findArtistId(artist) ?: return@withContext emptyList()
            val res = fetchParsed<DzrListResponse<DzrAlbumSummary>>(
                apiUrl("artist/$artistId/albums", "limit" to "50"),
                "ARTIST_ALBUMS"
            )
            val albums = res.data
                .filter { it.recordType == null || it.recordType in setOf("album", "ep") }
                .mapNotNull { it.toDomainAlbum() }
            BackendDebugger.logResponse(id, "ARTIST_ALBUMS", albums.size, "")
            albums
        } catch (e: Exception) {
            BackendDebugger.logError(id, "ARTIST_ALBUMS", e)
            emptyList()
        }
    }

    override suspend fun getArtistInfo(name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        try {
            val artistId = findArtistId(name) ?: return@withContext null
            val full = fetchParsed<DzrArtistFull>(
                apiUrl("artist/$artistId"),
                "ARTIST_INFO"
            )
            val imageUrl = (full.pictureXl ?: full.pictureBig).validDeezerImage()
            BackendDebugger.logResponse(id, "ARTIST_INFO", 1, "")
            ArtistInfo(
                name = full.name ?: name,
                bio = "",
                tags = emptyList(),
                imageUrl = imageUrl
            )
        } catch (e: Exception) {
            BackendDebugger.logError(id, "ARTIST_INFO", e)
            null
        }
    }

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            val deezerId = resolveDeezerId(track) ?: return@withContext emptyList()
            val res = fetchParsed<DzrListResponse<DzrTrack>>(
                apiUrl("track/$deezerId/radio", "limit" to "15"),
                "RECOMMENDATIONS"
            )
            val tracks = res.data.mapNotNull { it.toDomainTrack() }
            BackendDebugger.logResponse(id, "RECOMMENDATIONS", tracks.size, "")
            tracks
        } catch (e: Exception) {
            BackendDebugger.logError(id, "RECOMMENDATIONS", e)
            emptyList()
        }
    }

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed<DzrListResponse<DzrTrack>>(
                apiUrl("chart/0/tracks", "limit" to "10"),
                "TRENDING"
            )
            val tracks = res.data.mapNotNull { it.toDomainTrack() }
            BackendDebugger.logResponse(id, "TRENDING", tracks.size, "")
            tracks
        } catch (e: Exception) {
            BackendDebugger.logError(id, "TRENDING", e)
            emptyList()
        }
    }

    private suspend fun findArtistId(name: String): Long? {
        return try {
            val res = fetchParsed<DzrListResponse<DzrArtistRef>>(
                apiUrl("search/artist", "q" to name, "limit" to "5"),
                "ARTIST_LOOKUP"
            )
            val exact = res.data.firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
            (exact ?: res.data.firstOrNull())?.id
        } catch (e: Exception) {
            BackendDebugger.logError(id, "ARTIST_LOOKUP", e)
            null
        }
    }

    private suspend fun resolveDeezerId(track: Track): Long? {
        if (track.id.startsWith("deezer:")) {
            track.id.removePrefix("deezer:").toLongOrNull()?.let { return it }
        }
        return try {
            val query = "${Normalizer.primaryArtist(track)} ${track.title}"
            val res = fetchParsed<DzrListResponse<DzrTrack>>(
                apiUrl("search", "q" to query, "limit" to "5"),
                "RECOMMENDATIONS_LOOKUP"
            )
            val primaryArtistLower = Normalizer.primaryArtist(track).lowercase()
            val titleLower = track.title.lowercase()
            val match = res.data.firstOrNull { t ->
                t.title?.lowercase()?.contains(titleLower) == true &&
                        t.artist?.name?.lowercase()?.let { a ->
                            a.contains(primaryArtistLower) || primaryArtistLower.contains(a)
                        } == true
            } ?: res.data.firstOrNull()
            match?.id
        } catch (e: Exception) {
            BackendDebugger.logError(id, "RECOMMENDATIONS_LOOKUP", e)
            null
        }
    }
}
