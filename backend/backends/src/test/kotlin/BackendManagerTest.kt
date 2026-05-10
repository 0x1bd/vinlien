import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.StreamResolutionResult
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.Track
import java.util.UUID

class AggregationEngineTest {

    private lateinit var engine: AggregationEngine

    @BeforeEach
    fun setup() {
        engine = AggregationEngine(
            listOf(
                CatalogProvider(
                    id = "itunes",
                    tracks = listOf(
                        track("itunes:thriller", "Michael Jackson", "Thriller", albumTitle = "Thriller"),
                        track("itunes:rick", "Rick Astley", "Never Gonna Give You Up"),
                        track("itunes:daft-one-more-time", "Daft Punk", "One More Time", albumTitle = "Discovery")
                    ),
                    albums = listOf(discoveryAlbum("itunes:album:1:::Daft Punk:::Discovery")),
                    trending = listOf(track("itunes:trend", "Daft Punk", "Harder Better Faster Stronger"))
                ),
                CatalogProvider(
                    id = "mb",
                    tracks = listOf(
                        track("mb:daft-digital-love", "Daft Punk", "Digital Love", albumTitle = "Discovery"),
                        track("mb:thriller", "Michael Jackson", "Thriller", artworkUrl = "https://img.test/thriller.jpg")
                    ),
                    albums = listOf(discoveryAlbum("mb:album:Daft Punk:::Discovery")),
                    trending = listOf(track("mb:trend", "Michael Jackson", "Billie Jean"))
                ),
                AudioProvider(
                    id = "sc",
                    tracks = listOf(
                        track("sc:lofi", "Lofi Artist", "Lofi Hip Hop"),
                        track("sc:rick-stream", "Rick Astley", "Never Gonna Give You Up")
                    )
                ),
                AudioProvider(
                    id = "ytmusic",
                    tracks = listOf(track("ytmusic:rick-stream", "Rick Astley", "Never Gonna Give You Up"))
                )
            )
        )
    }

    @Test
    fun `test basic search aggregates results`() = runTest {
        val results = engine.searchTracks("Michael Jackson Thriller")
        assertTrue(results.isNotEmpty(), "Search should return tracks")
        assertTrue(results.first().title.contains("Thriller", ignoreCase = true), "First result should match query")
        assertTrue(results.first().id.isNotBlank(), "Track should have an ID")
    }

    @Test
    fun `test search returns merged results from multiple providers`() = runTest {
        val results = engine.searchTracks("Daft Punk")
        assertTrue(results.any { it.id.startsWith("itunes:") })
        assertTrue(results.any { it.id.startsWith("mb:") })
    }

    @Test
    fun `test trending aggregates from all providers`() = runTest {
        val trending = engine.getTrending()
        assertEquals(2, trending.size)
    }

    @Test
    fun `test album search and retrieval with track merging`() = runTest {
        val albums = engine.searchAlbums("Discovery Daft Punk")
        assertTrue(albums.isNotEmpty(), "Should find albums")

        val targetAlbum = albums.first()
        val fullAlbum = engine.getAlbum(targetAlbum.id)
        assertNotNull(fullAlbum, "Full album should be resolved")
        assertEquals(targetAlbum.title, fullAlbum?.title)
        assertTrue(fullAlbum!!.tracks.isNotEmpty(), "Album should contain tracks from at least one provider")
    }

    @Test
    fun `test native SoundCloud stream resolution`() = runTest {
        val scTrack = Track(
            id = "sc:lofi",
            title = "Lofi Hip Hop",
            artist = "Lofi Artist",
            durationMs = 180_000L
        )

        val streamUrl = engine.resolveStream(scTrack)
        assertEquals("https://streams.test/sc:lofi", streamUrl)
    }

    @Test
    fun `test stream resolution fallback for non-native tracks`() = runTest {
        val tracks = engine.searchTracks("Rick Astley Never Gonna Give You Up")
        val itunesTrack = tracks.firstOrNull { it.id.startsWith("itunes:") }
            ?: error("Expected deterministic iTunes fixture result")

        val streamUrl = engine.resolveStream(itunesTrack)
        assertTrue(streamUrl.startsWith("https://streams.test/"), "Fallback stream URL should be a valid test link")
    }

    @Test
    fun `test invalid track throws exception on stream resolution`() = runTest {
        val random = UUID.randomUUID().toString()
        val fakeTrack = Track(
            id = "fake:999999",
            title = random,
            artist = random,
            durationMs = 0L,
            canonicalId = "$random:::$random"
        )

        val result = runCatching { engine.resolveStream(fakeTrack) }
        assertTrue(result.isFailure, "Expected resolveStream to fail")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("No stream available") == true,
            "Exception message: '${result.exceptionOrNull()?.message}'"
        )
    }

    private class CatalogProvider(
        override val id: String,
        private val tracks: List<Track> = emptyList(),
        private val albums: List<Album> = emptyList(),
        private val trending: List<Track> = emptyList()
    ) : MusicProvider {
        override val name = "Catalog $id"
        override val capabilities = buildSet {
            if (tracks.isNotEmpty()) add(Capability.TRACK_SEARCH)
            if (albums.isNotEmpty()) {
                add(Capability.ALBUM_SEARCH)
                add(Capability.ALBUM_TRACKS)
            }
            if (trending.isNotEmpty()) add(Capability.TRENDING)
        }

        override suspend fun searchTracks(query: String): List<Track> =
            tracks.filter { matchesQuery("${it.artist} ${it.title} ${it.albumTitle.orEmpty()}", query) }

        override suspend fun searchAlbums(query: String): List<Album> =
            albums.filter { matchesQuery("${it.artist} ${it.title}", query) }

        override suspend fun getAlbum(artist: String, albumTitle: String): Album? =
            albums.firstOrNull {
                it.artist.equals(artist, ignoreCase = true) &&
                        it.title.equals(albumTitle, ignoreCase = true)
            }

        override suspend fun getTrending(): List<Track> = trending
    }

    private class AudioProvider(
        override val id: String,
        private val tracks: List<Track>
    ) : MusicProvider {
        override val name = "Audio $id"
        override val capabilities = setOf(Capability.AUDIO_STREAM)

        override suspend fun searchAudio(query: String): List<Track> =
            tracks.filter { matchesQuery("${it.artist} ${it.title}", query) }

        override suspend fun resolveStream(track: Track): StreamResolutionResult =
            StreamResolutionResult.Success("https://streams.test/${track.id}", id)
    }
}

private fun discoveryAlbum(id: String): Album =
    Album(
        id = id,
        title = "Discovery",
        artist = "Daft Punk",
        artworkUrl = "https://img.test/discovery.jpg",
        year = 2001,
        tracks = listOf(
            track("album:one-more-time", "Daft Punk", "One More Time", albumTitle = "Discovery"),
            track("album:digital-love", "Daft Punk", "Digital Love", albumTitle = "Discovery")
        )
    )

private fun track(
    id: String,
    artist: String,
    title: String,
    albumTitle: String? = null,
    artworkUrl: String? = null
): Track =
    Track(
        id = id,
        title = title,
        artist = artist,
        artists = listOf(artist),
        durationMs = 180_000L,
        artworkUrl = artworkUrl,
        albumTitle = albumTitle
    )

private fun matchesQuery(text: String, query: String): Boolean {
    val haystack = text.lowercase()
    val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    return tokens.all { haystack.contains(it) }
}
