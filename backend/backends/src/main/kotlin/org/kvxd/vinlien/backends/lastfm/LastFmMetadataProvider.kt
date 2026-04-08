package org.kvxd.vinlien.backends.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.kvxd.vinlien.backends.MetadataProvider
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder
import java.util.UUID

@Serializable
private data class LfmResponse(
    val results: LfmResults? = null,
    val similartracks: LfmWrapper? = null,
    val tracks: LfmWrapper? = null,
    val album: LfmAlbum? = null,
    val artist: LfmArtistData? = null,
    val track: LfmTrackInfo? = null
)

@Serializable
private data class LfmResults(
    val trackmatches: LfmWrapper? = null,
    val albummatches: LfmWrapper? = null
)

@Serializable
private data class LfmWrapper(
    val track: JsonElement? = null,
    val album: JsonElement? = null
)

@Serializable
private data class LfmTrack(
    val name: String? = null,
    val artist: JsonElement? = null,
    val duration: String? = null,
    val url: String? = null
) {
    fun toTrack(fallbackArtist: String? = null): Track? {
        val title = name ?: return null
        val resolvedArtist = getArtistName() ?: fallbackArtist ?: return null
        val durationMs = duration?.toLongOrNull()?.let { it * 1000L } ?: 0L

        return Track(
            id = "lastfm:${UUID.randomUUID()}",
            title = title,
            artist = resolvedArtist,
            durationMs = durationMs,
            canonicalId = "${resolvedArtist.lowercase().trim()}:::${title.lowercase().trim()}",
            lastFmUrl = url
        )
    }

    private fun getArtistName(): String? {
        if (artist == null) return null
        return try {
            if (artist is JsonPrimitive) artist.content else artist.jsonObject["name"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
private data class LfmAlbum(
    val name: String? = null,
    val artist: String? = null,
    val image: List<LfmImage> = emptyList(),
    val tracks: LfmWrapper? = null
) {
    fun toDomainAlbum(): Album? {
        val title = name ?: return null
        val artistName = artist ?: return null
        val artworkUrl = image.lastOrNull()?.text?.takeIf { it.isNotBlank() }

        return Album(
            id = "lastfm:album:${artistName}:::${title}",
            title = title,
            artist = artistName,
            artworkUrl = artworkUrl
        )
    }
}

@Serializable
private data class LfmImage(
    @SerialName("#text") val text: String? = null,
    val size: String? = null
)

@Serializable
private data class LfmArtistData(
    val name: String? = null,
    val bio: LfmBio? = null,
    val tags: LfmTagsWrapper? = null
)

@Serializable
private data class LfmBio(val summary: String? = null)

@Serializable
private data class LfmTagsWrapper(val tag: JsonElement? = null)

@Serializable
private data class LfmTag(val name: String? = null)

@Serializable
private data class LfmTrackInfo(val album: LfmAlbumInfo? = null)

@Serializable
private data class LfmAlbumInfo(val image: List<LfmImage> = emptyList())


class LastFmMetadataProvider(private val apiKey: String) : MetadataProvider {
    override val name = "Last.fm"

    private fun apiUrl(method: String, vararg params: Pair<String, String>): String {
        val base = "http://ws.audioscrobbler.com/2.0/?method=$method&api_key=$apiKey&format=json"
        return base + params.joinToString("") { (k, v) -> "&$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private suspend inline fun fetchParsed(url: String): LfmResponse =
        sharedJson.decodeFromString(fetch(url))

    private inline fun <reified T> JsonElement?.parseLfmList(): List<T> {
        if (this == null) return emptyList()
        return try {
            when (this) {
                is JsonArray -> sharedJson.decodeFromJsonElement(this)
                is JsonObject -> listOf(sharedJson.decodeFromJsonElement(this))
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val res = fetchParsed(apiUrl("track.search", "track" to query, "limit" to "15"))
        val tracks = res.results?.trackmatches?.track.parseLfmList<LfmTrack>()
        tracks.mapNotNull { it.toTrack() }.withCovers()
    }

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val res =
            fetchParsed(apiUrl("track.getsimilar", "artist" to track.artist, "track" to track.title, "limit" to "15"))
        val tracks = res.similartracks?.track.parseLfmList<LfmTrack>()
        tracks.mapNotNull { it.toTrack() }.withCovers()
    }

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        val res = fetchParsed(apiUrl("chart.gettoptracks", "limit" to "6"))
        val tracks = res.tracks?.track.parseLfmList<LfmTrack>()
        tracks.mapNotNull { it.toTrack() }.withCovers()
    }

    override suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed(apiUrl("album.search", "album" to query))
            val albums = res.results?.albummatches?.album.parseLfmList<LfmAlbum>()
            albums.mapNotNull { it.toDomainAlbum() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAlbum(id: String): Album? = withContext(Dispatchers.IO) {
        try {
            val cleanId = id.removePrefix("lastfm:album:")
            val parts = cleanId.split(":::", limit = 2)
            if (parts.size != 2) return@withContext null

            val res =
                fetchParsed(apiUrl("album.getinfo", "artist" to parts[0], "album" to parts[1], "autocorrect" to "1"))
            val albumObj = res.album ?: return@withContext null

            val artistName = albumObj.artist ?: parts[0]
            val title = albumObj.name ?: parts[1]
            val artworkUrl = albumObj.image.lastOrNull()?.text?.takeIf { it.isNotBlank() }

            val rawTracks = albumObj.tracks?.track.parseLfmList<LfmTrack>()
            val tracks = rawTracks.mapNotNull { it.toTrack(fallbackArtist = artistName) }

            Album(id = id, title = title, artist = artistName, artworkUrl = artworkUrl, tracks = tracks)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getArtistInfo(name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed(apiUrl("artist.getinfo", "artist" to name, "autocorrect" to "1"))
            val artistObj = res.artist ?: return@withContext null

            val rawBio = artistObj.bio?.summary ?: ""
            val cleanBio = rawBio.substringBefore("<a href").trim()

            val tags = artistObj.tags?.tag.parseLfmList<LfmTag>().mapNotNull { it.name }.take(5)

            ArtistInfo(
                name = artistObj.name ?: name,
                bio = cleanBio,
                tags = tags
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun List<Track>.withCovers(): List<Track> = withContext(Dispatchers.IO) {
        map { track ->
            async {
                val cover = fetchAlbumCover(track.artist, track.title)
                cover?.let { track.copy(artworkUrl = it) } ?: track
            }
        }.awaitAll()
    }

    private suspend fun fetchAlbumCover(artist: String, title: String): String? = try {
        val res = fetchParsed(apiUrl("track.getInfo", "artist" to artist, "track" to title, "autocorrect" to "1"))
        val images = res.track?.album?.image ?: emptyList()

        listOf("extralarge", "mega", "large").firstNotNullOfOrNull { size ->
            images.firstOrNull { it.size == size }?.text
                ?.takeIf { it.isNotBlank() && !it.contains("2a96cbd8b46e442fc41c2b86b821562f") }
        }
    } catch (e: Exception) {
        null
    }
}