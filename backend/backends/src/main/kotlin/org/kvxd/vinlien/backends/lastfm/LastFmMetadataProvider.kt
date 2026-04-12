package org.kvxd.vinlien.backends.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.models.Album
import org.kvxd.vinlien.shared.models.ArtistInfo
import org.kvxd.vinlien.shared.models.Track
import java.net.URLDecoder
import java.net.URLEncoder

private val PLACEHOLDER_IMAGE_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

@Serializable
private data class LfmResponse(
    val results: LfmResults? = null,
    val similartracks: LfmWrapper? = null,
    val tracks: LfmWrapper? = null,
    val album: LfmAlbum? = null,
    val artist: LfmArtistData? = null,
    val track: LfmTrackInfo? = null,
    val topalbums: LfmTopAlbumsWrapper? = null
)

@Serializable
private data class LfmTopAlbumsWrapper(val album: JsonElement? = null)

@Serializable
private data class LfmTopAlbumItem(
    val name: String? = null,
    val artist: JsonElement? = null,
    val image: List<LfmImage> = emptyList()
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
    val duration: JsonElement? = null,
    val listeners: String? = null,
    val url: String? = null,
    val image: List<LfmImage> = emptyList()
) {
    fun toTrack(fallbackArtist: String? = null): Track? {
        val title = name ?: return null
        val resolvedArtist = artistName() ?: fallbackArtist ?: return null
        val durationSec = when (val d = duration) {
            is JsonPrimitive -> if (d.isString) d.content.toLongOrNull() else d.longOrNull
            else -> null
        } ?: 0L

        val artworkUrl = listOf("mega", "extralarge", "large").firstNotNullOfOrNull { size ->
            image.firstOrNull { it.size == size }?.text
                ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }
        }

        return Track(
            id = "lastfm:${Normalizer.canonicalIdFor(resolvedArtist, title)}",
            title = title,
            artist = resolvedArtist,
            durationMs = durationSec * 1000L,
            artworkUrl = artworkUrl,
            canonicalId = Normalizer.canonicalIdFor(resolvedArtist, title),
            lastFmUrl = url
        )
    }

    private fun artistName(): String? {
        if (artist == null) return null
        return try {
            if (artist is JsonPrimitive) artist.content else artist.jsonObject["name"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
private data class LfmWiki(val published: String? = null)

@Serializable
private data class LfmAlbum(
    val name: String? = null,
    val artist: String? = null,
    val image: List<LfmImage> = emptyList(),
    val tracks: LfmWrapper? = null,
    val wiki: LfmWiki? = null
)

@Serializable
private data class LfmImage(
    @SerialName("#text") val text: String? = null,
    val size: String? = null
)

@Serializable
private data class LfmArtistData(
    val name: String? = null,
    val bio: LfmBio? = null,
    val tags: LfmTagsWrapper? = null,
    val image: List<LfmImage> = emptyList()
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


class LastFmMetadataProvider(
    private val apiKey: String,
    private val username: String = ""
) : MusicProvider {
    override val id = "lastfm"
    override val name = "Last.fm"
    override val capabilities = setOf(
        Capability.TRACK_SEARCH,
        Capability.ALBUM_SEARCH,
        Capability.ARTIST_INFO,
        Capability.ARTIST_ALBUMS,
        Capability.ALBUM_TRACKS,
        Capability.RECOMMENDATIONS,
        Capability.TRENDING
    )

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

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val res = fetchParsed(apiUrl("track.search", "track" to query, "limit" to "15"))
        val tracks = res.results?.trackmatches?.track.parseLfmList<LfmTrack>().mapNotNull { it.toTrack() }
        if (username.isBlank()) return@withContext tracks
        coroutineScope {
            tracks.map { track ->
                async {
                    if (track.artworkUrl != null) return@async track
                    val params = buildList {
                        add("artist" to track.artist)
                        add("track" to track.title)
                        add("autocorrect" to "1")
                        add("username" to username)
                    }
                    val artworkUrl = runCatching {
                        fetchParsed(apiUrl("track.getinfo", *params.toTypedArray()))
                            .track?.album?.image?.let { images ->
                                listOf("extralarge", "large", "mega").firstNotNullOfOrNull { size ->
                                    images.firstOrNull { it.size == size }?.text
                                        ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }
                                }
                            }
                    }.getOrNull()
                    if (artworkUrl != null) track.copy(artworkUrl = artworkUrl) else track
                }
            }.awaitAll()
        }
    }

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val primaryArtist = Normalizer.primaryArtist(track)

        val urlPair = parseLastFmUrl(track.lastFmUrl)
        if (urlPair != null) {
            val (urlArtist, urlTitle) = urlPair
            val res = fetchParsed(apiUrl("track.getsimilar", "artist" to urlArtist, "track" to urlTitle, "limit" to "15"))
            val rawTracks = res.similartracks?.track.parseLfmList<LfmTrack>()
            if (rawTracks.isNotEmpty()) return@withContext rawTracks.mapNotNull { it.toTrack() }
        }

        val (queryArtist, queryTitle) = findMostPopularLastFmMatch(track.title, primaryArtist)
            ?: (primaryArtist to track.title)
        val res = fetchParsed(apiUrl("track.getsimilar", "artist" to queryArtist, "track" to queryTitle, "limit" to "15"))
        res.similartracks?.track.parseLfmList<LfmTrack>().mapNotNull { it.toTrack() }
    }

    private fun parseLastFmUrl(url: String?): Pair<String, String>? {
        if (url == null) return null
        val match = Regex("""last\.fm/music/([^/_][^/]*)/_/([^?#]+)""").find(url) ?: return null
        val artist = URLDecoder.decode(match.groupValues[1].replace("+", " "), "UTF-8")
        val title = URLDecoder.decode(match.groupValues[2].replace("+", " "), "UTF-8")
        return artist to title
    }

    private suspend fun findMostPopularLastFmMatch(title: String, primaryArtist: String): Pair<String, String>? {
        val raw = runCatching {
            fetchParsed(apiUrl("track.search", "track" to title, "limit" to "10"))
                .results?.trackmatches?.track.parseLfmList<LfmTrack>()
        }.getOrNull() ?: return null

        val normalizedPrimary = primaryArtist.lowercase()
        val best = raw.sortedByDescending { it.listeners?.toLongOrNull() ?: 0L }
            .firstOrNull { t ->
                t.toTrack()?.artist?.lowercase()
                    ?.let { a -> a.contains(normalizedPrimary) || normalizedPrimary.contains(a) } == true
            } ?: return null

        return best.toTrack()?.let { it.artist to it.title }
    }

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        val res = fetchParsed(apiUrl("chart.gettoptracks", "limit" to "6"))
        res.tracks?.track.parseLfmList<LfmTrack>().mapNotNull { it.toTrack() }
    }

    override suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed(apiUrl("album.search", "album" to query))
            res.results?.albummatches?.album.parseLfmList<LfmAlbum>().mapNotNull { it.toDomainAlbum() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAlbum(artist: String, albumTitle: String): Album? = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed(
                apiUrl("album.getinfo", "artist" to artist, "album" to albumTitle, "autocorrect" to "1")
            )
            val albumObj = res.album ?: return@withContext null

            val resolvedArtist = albumObj.artist ?: artist
            val resolvedTitle = albumObj.name ?: albumTitle
            val artworkUrl = albumObj.image.lastOrNull()?.text
                ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }

            val wikiYear = albumObj.wiki?.published
                ?.let { Regex("""\b(\d{4})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val tracks = albumObj.tracks?.track.parseLfmList<LfmTrack>()
                .mapNotNull { it.toTrack(fallbackArtist = resolvedArtist) }

            Album(
                id = "lastfm:album:${resolvedArtist}:::${resolvedTitle}",
                title = resolvedTitle,
                artist = resolvedArtist,
                artworkUrl = artworkUrl,
                year = wikiYear,
                tracks = tracks
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getArtistAlbums(artist: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed(
                apiUrl("artist.gettopalbums", "artist" to artist, "autocorrect" to "1", "limit" to "50")
            )
            res.topalbums?.album.parseLfmList<LfmTopAlbumItem>().mapNotNull { item ->
                val title = item.name ?: return@mapNotNull null
                val artistName = when (val a = item.artist) {
                    is JsonObject -> a["name"]?.jsonPrimitive?.content ?: artist
                    is JsonPrimitive -> a.content
                    else -> artist
                }
                val artworkUrl = item.image.lastOrNull()?.text
                    ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }
                Album(
                    id = "lastfm:album:${artistName}:::${title}",
                    title = title,
                    artist = artistName,
                    artworkUrl = artworkUrl
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtistInfo(name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        try {
            val params = buildList {
                add("artist" to name)
                add("autocorrect" to "1")
                if (username.isNotBlank()) add("username" to username)
            }
            val res = fetchParsed(apiUrl("artist.getinfo", *params.toTypedArray()))
            val artistObj = res.artist ?: return@withContext null

            val rawBio = artistObj.bio?.summary ?: ""
            val cleanBio = rawBio.substringBefore("<a href").trim()
            val tags = artistObj.tags?.tag.parseLfmList<LfmTag>().mapNotNull { it.name }.take(5)
            val imageUrl = listOf("mega", "extralarge", "large").firstNotNullOfOrNull { size ->
                artistObj.image.firstOrNull { it.size == size }?.text
                    ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }
            }

            ArtistInfo(
                name = artistObj.name ?: name,
                bio = cleanBio,
                tags = tags,
                imageUrl = imageUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun LfmAlbum.toDomainAlbum(): Album? {
        val title = name ?: return null
        val artistName = artist ?: return null
        val artworkUrl = image.lastOrNull()?.text
            ?.takeIf { it.isNotBlank() && !it.contains(PLACEHOLDER_IMAGE_HASH) }
        return Album(
            id = "lastfm:album:${artistName}:::${title}",
            title = title,
            artist = artistName,
            artworkUrl = artworkUrl
        )
    }
}
