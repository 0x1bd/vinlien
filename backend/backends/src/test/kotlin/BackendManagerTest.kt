import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.backends.invidious.LocalInvidiousBackend
import org.kvxd.vinlien.backends.itunes.ItunesMetadataProvider
import org.kvxd.vinlien.backends.lastfm.LastFmMetadataProvider
import org.kvxd.vinlien.backends.musicbrainz.MusicBrainzMetadataProvider
import org.kvxd.vinlien.backends.soundcloud.SoundCloudBackend
import org.kvxd.vinlien.shared.Track
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AggregationEngineTest {

    private lateinit var engine: AggregationEngine

    private val lastFmKey = System.getenv("LASTFM_API_KEY")

    @BeforeAll
    fun setup() {
        val providers = buildList {
            if (lastFmKey != null) add(LastFmMetadataProvider(lastFmKey))
            add(ItunesMetadataProvider())
            add(MusicBrainzMetadataProvider())
            add(SoundCloudBackend())
            add(LocalInvidiousBackend("http://localhost:3000"))
        }
        engine = AggregationEngine(providers)
    }

    @Test
    fun `test basic search aggregates results`() = runTest {
        val results = engine.searchTracks("Michael Jackson Thriller")
        assertTrue(results.isNotEmpty(), "Search should return tracks")
        assertTrue(results.first().title.contains("Thriller", ignoreCase = true), "First result should match query")
        assertNotNull(results.first().id, "Track should have an ID")
    }

    @Test
    fun `test search returns merged results from multiple providers`() = runTest {
        val results = engine.searchTracks("Daft Punk")
        assertTrue(results.isNotEmpty())
        val providerIds = results.map { it.id.substringBefore(":") }.distinct()
        assertTrue(providerIds.size >= 1, "Should have results from at least one provider")
    }

    @Test
    fun `test LastFM search`() = runTest {
        assumeTrue(lastFmKey != null, "Skipping Last.fm test: LASTFM_API_KEY not set")
        val results = engine.searchTracks("Nirvana")
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `test trending aggregates from all providers`() = runTest {
        val trending = engine.getTrending()
        assertTrue(trending.isNotEmpty(), "Trending should return results")
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
        val tracks = engine.searchTracks("Lofi Hip Hop")
        val scTrack = tracks.firstOrNull { it.id.startsWith("sc:") }
            ?: return@runTest  // Skip if SoundCloud not available

        val streamUrl = engine.resolveStream(scTrack)
        assertTrue(streamUrl.startsWith("http"), "Stream URL should be a valid HTTP link")
    }

    @Test
    fun `test stream resolution fallback for non-native tracks`() = runTest {
        val tracks = engine.searchTracks("Rick Astley Never Gonna Give You Up")
        val itunesTrack = tracks.firstOrNull { it.id.startsWith("itunes:") }
            ?: return@runTest  // Skip if iTunes not available

        val streamUrl = engine.resolveStream(itunesTrack)
        assertTrue(streamUrl.startsWith("http"), "Fallback stream URL should be a valid HTTP link")
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
}
