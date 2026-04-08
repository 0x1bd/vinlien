package org.kvxd.vinlien.backends.itunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.MetadataProvider
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder

@Serializable
private data class ItunesResponse(
    val results: List<ItunesResult> = emptyList()
)

@Serializable
private data class ItunesResult(
    val wrapperType: String? = null,
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val trackTimeMillis: Long? = null,
    val artworkUrl100: String? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val releaseDate: String? = null,
    val trackCount: Int? = null
) {
    fun toTrack(): Track? {
        val title = trackName ?: return null
        val artist = artistName ?: return null
        val durationMs = trackTimeMillis ?: 0L
        if (durationMs > 600_000L) return null
        val artworkUrl = artworkUrl100?.replace("100x100bb", "512x512bb") ?: return null
        val id = trackId ?: return null

        return Track(
            id = "itunes:$id",
            title = title,
            artist = artist,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            canonicalId = "${artist.lowercase().trim()}:::${title.lowercase().trim()}"
        )
    }

    fun toAlbum(): Album? {
        val title = collectionName ?: return null
        val artist = artistName ?: return null
        val id = collectionId ?: return null
        val artworkUrl = artworkUrl100?.replace("100x100bb", "512x512bb")
        val year = releaseDate?.take(4)?.toIntOrNull()

        return Album("itunes:$id", title, artist, artworkUrl, year)
    }
}

@Serializable
private data class ItunesRssResponse(
    val feed: ItunesFeed? = null
)

@Serializable
private data class ItunesFeed(
    val entry: List<ItunesRssEntry> = emptyList()
)

@Serializable
private data class ItunesRssEntry(
    @SerialName("im:name") val name: ItunesLabel? = null,
    @SerialName("im:artist") val artist: ItunesLabel? = null,
    @SerialName("im:image") val image: List<ItunesLabel> = emptyList()
)

@Serializable
private data class ItunesLabel(
    val label: String? = null
)

class ItunesMetadataProvider : MetadataProvider {
    override val name = "iTunes"

    private suspend inline fun <reified T> fetchParsed(url: String): T =
        sharedJson.decodeFromString(fetch(url))

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val res =
            fetchParsed<ItunesResponse>("https://itunes.apple.com/search?term=${query.encoded}&limit=20&media=music")
        res.results.mapNotNull { it.toTrack() }
    }

    override suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        val res =
            fetchParsed<ItunesResponse>("https://itunes.apple.com/search?term=${query.encoded}&entity=album&limit=15")
        res.results.mapNotNull { it.toAlbum() }
    }

    override suspend fun getArtistAlbums(artist: String): List<Album> = withContext(Dispatchers.IO) {
        val res =
            fetchParsed<ItunesResponse>("https://itunes.apple.com/search?term=${artist.encoded}&entity=album&attribute=allArtistTerm&limit=50")
        res.results.filter { (it.trackCount ?: 1) > 1 }.mapNotNull { it.toAlbum() }
    }

    override suspend fun getAlbum(id: String): Album? = withContext(Dispatchers.IO) {
        try {
            val cleanId = id.removePrefix("itunes:")
            val res = fetchParsed<ItunesResponse>("https://itunes.apple.com/lookup?id=$cleanId&entity=song")

            var album: Album? = null
            val tracks = mutableListOf<Track>()

            for (el in res.results) {
                if (el.wrapperType == "collection") {
                    album = el.toAlbum()
                } else if (el.wrapperType == "track") {
                    el.toTrack()?.let { tracks.add(it) }
                }
            }
            album?.copy(tracks = tracks)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        search(track.artist).filter { it.canonicalId != track.canonicalId }
    }

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val res = fetchParsed<ItunesRssResponse>("https://itunes.apple.com/us/rss/topsongs/limit=10/json")
            res.feed?.entry?.mapNotNull { entry ->
                val title = entry.name?.label ?: return@mapNotNull null
                val artist = entry.artist?.label ?: return@mapNotNull null
                val artworkUrl = entry.image.lastOrNull()?.label ?: return@mapNotNull null

                Track(
                    id = "itunes:rss:${title.hashCode()}",
                    title = title,
                    artist = artist,
                    durationMs = 0L,
                    artworkUrl = artworkUrl,
                    canonicalId = "${artist.lowercase().trim()}:::${title.lowercase().trim()}"
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchCover(artist: String, title: String): String? = try {
        val res =
            fetchParsed<ItunesResponse>("https://itunes.apple.com/search?term=${"$artist $title".encoded}&limit=5&media=music")
        val match = res.results.firstOrNull { el ->
            el.artistName?.lowercase()?.contains(artist.lowercase()) == true &&
                    el.trackName?.lowercase()?.contains(title.lowercase()) == true
        } ?: res.results.firstOrNull()

        match?.artworkUrl100?.replace("100x100bb", "512x512bb")
    } catch (e: Exception) {
        null
    }

    private val String.encoded get() = URLEncoder.encode(this, "UTF-8")
}