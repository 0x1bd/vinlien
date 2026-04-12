package org.kvxd.vinlien.backends.itunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.normalized
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder

private val String.urlEncoded get() = URLEncoder.encode(this, "UTF-8")

@Serializable
private data class ItunesResponse(val results: List<ItunesResult> = emptyList())

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
            canonicalId = Normalizer.canonicalIdFor(artist, title)
        )
    }

    fun toAlbum(): Album? {
        val title = collectionName ?: return null
        val artist = artistName ?: return null
        val collId = collectionId ?: return null
        val artworkUrl = artworkUrl100?.replace("100x100bb", "512x512bb")
        val year = releaseDate?.take(4)?.toIntOrNull()
        return Album("itunes:album:${collId}:::${artist}:::${title}", title, artist, artworkUrl, year)
    }
}

@Serializable
private data class ItunesRssResponse(val feed: ItunesFeed? = null)

@Serializable
private data class ItunesFeed(val entry: List<ItunesRssEntry> = emptyList())

@Serializable
private data class ItunesRssEntry(
    @SerialName("im:name") val name: ItunesLabel? = null,
    @SerialName("im:artist") val artist: ItunesLabel? = null,
    @SerialName("im:image") val image: List<ItunesLabel> = emptyList()
)

@Serializable
private data class ItunesLabel(val label: String? = null)


class ItunesMetadataProvider : MusicProvider {
    override val id = "itunes"
    override val name = "iTunes"
    override val capabilities = setOf(
        Capability.TRACK_SEARCH,
        Capability.ALBUM_SEARCH,
        Capability.ARTIST_ALBUMS,
        Capability.RECOMMENDATIONS,
        Capability.TRENDING
    )

    private suspend inline fun <reified T> fetchParsed(url: String): T =
        sharedJson.decodeFromString(fetch(url))

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        fetchParsed<ItunesResponse>(
            "https://itunes.apple.com/search?term=${query.urlEncoded}&limit=20&media=music"
        ).results.mapNotNull { it.toTrack() }
    }

    override suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        fetchParsed<ItunesResponse>(
            "https://itunes.apple.com/search?term=${query.urlEncoded}&entity=album&limit=15"
        ).results.mapNotNull { it.toAlbum() }
    }

    override suspend fun getArtistAlbums(artist: String): List<Album> = withContext(Dispatchers.IO) {
        fetchParsed<ItunesResponse>(
            "https://itunes.apple.com/search?term=${artist.urlEncoded}&entity=album&attribute=allArtistTerm&limit=50"
        ).results.filter { (it.trackCount ?: 1) > 1 }.mapNotNull { it.toAlbum() }
    }

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val primaryArtist = Normalizer.primaryArtist(track)
        val primaryNormalized = primaryArtist.normalized()
        searchTracks(primaryArtist).filter { candidate ->
            candidate.canonicalId != track.canonicalId &&
                    candidate.artist.normalized().let { a ->
                        a.contains(primaryNormalized) || primaryNormalized.contains(a)
                    }
        }
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
                    canonicalId = Normalizer.canonicalIdFor(artist, title)
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
