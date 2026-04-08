import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kvxd.vinlien.backends.BackendManager
import org.kvxd.vinlien.backends.invidious.LocalInvidiousBackend
import org.kvxd.vinlien.backends.itunes.ItunesMetadataProvider
import org.kvxd.vinlien.backends.lastfm.LastFmMetadataProvider
import org.kvxd.vinlien.backends.musicbrainz.MusicBrainzMetadataProvider
import org.kvxd.vinlien.backends.soundcloud.SoundCloudBackend
import org.kvxd.vinlien.shared.Track
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendManagerTest {

    private lateinit var manager: BackendManager

    private val invidious = LocalInvidiousBackend("http://localhost:3000")
    private val itunes = ItunesMetadataProvider()
    private val soundCloud = SoundCloudBackend()
    private val musicBrainz = MusicBrainzMetadataProvider()

    private val lastFmKey = System.getenv("LASTFM_API_KEY")
    private val lastFm = LastFmMetadataProvider(lastFmKey ?: "dummy_key")

    @BeforeAll
    fun setup() {
        manager = BackendManager(
            metadataProviders = listOf(itunes, soundCloud, musicBrainz, lastFm, invidious),
            audioProviders = listOf(soundCloud, invidious)
        )
    }

    @Test
    fun `test basic search with fallback`() = runTest {
        val results = manager.search("Michael Jackson Thriller")

        assertTrue(results.isNotEmpty(), "Search should return tracks")
        assertTrue(results.first().title.contains("Thriller", ignoreCase = true), "First result should match query")
        assertNotNull(results.first().id, "Track should have an ID")
    }

    @Test
    fun `test targeted search per provider`() = runTest {
        val query = "Daft Punk"

        val itunesResults = manager.search(query, preferred = "iTunes")
        assertTrue(itunesResults.isNotEmpty())
        assertTrue(itunesResults.first().id.startsWith("itunes:"))

        val scResults = manager.search(query, preferred = "SoundCloud")
        assertTrue(scResults.isNotEmpty())
        assertTrue(scResults.first().id.startsWith("sc:"))

        val mbResults = manager.search(query, preferred = "MusicBrainz")
        assertTrue(mbResults.isNotEmpty())
        assertTrue(mbResults.first().id.startsWith("mb:"))
    }

    @Test
    fun `test LastFM targeted search`() = runTest {
        assumeTrue(lastFmKey != null, "Skipping Last.fm test: LASTFM_API_KEY environment variable not set")

        val results = manager.search("Nirvana", preferred = "Last.fm")
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().id.startsWith("lastfm:"))
    }

    @Test
    fun `test get trending uses fallback successfully`() = runTest {
        val trending = manager.getTrending()
        assertTrue(trending.isNotEmpty(), "Trending should return results from the first capable provider")
    }

    @Test
    fun `test album search and retrieval`() = runTest {
        val albums = manager.searchAlbums("Discovery Daft Punk", preferred = "iTunes")
        assertTrue(albums.isNotEmpty(), "Should find albums")

        val targetAlbum = albums.first()
        assertTrue(targetAlbum.id.startsWith("itunes:"), "Album should have correct prefix")

        val fullAlbum = manager.getAlbum(targetAlbum.id)
        assertNotNull(fullAlbum, "Full album should be resolved")
        assertEquals(targetAlbum.title, fullAlbum?.title)

        assertNotNull(fullAlbum?.tracks)
        assertTrue(fullAlbum!!.tracks.isNotEmpty(), "Album should contain tracks")
    }

    @Test
    fun `test native audio stream resolution (SoundCloud)`() = runTest {
        val scTracks = manager.search("Lofi Hip Hop", preferred = "SoundCloud")
        assertTrue(scTracks.isNotEmpty())

        val scTrack = scTracks.first()

        val streamUrl = manager.getStreamUrl(scTrack)

        assertNotNull(streamUrl)
        assertTrue(streamUrl.startsWith("http"), "Stream URL should be a valid HTTP link")
    }

    @Test
    fun `test audio stream resolution fallback (iTunes to Audio Providers)`() = runTest {
        val itunesTracks = manager.search("Rick Astley Never Gonna Give You Up", preferred = "iTunes")
        assertTrue(itunesTracks.isNotEmpty())

        val itunesTrack = itunesTracks.first()
        assertTrue(itunesTrack.id.startsWith("itunes:"), "Track should be an iTunes native track")

        val streamUrl = manager.getStreamUrl(itunesTrack)

        assertNotNull(streamUrl, "BackendManager should have successfully fallen back to an audio provider")
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

        val result = runCatching { manager.getStreamUrl(fakeTrack) }

        assertTrue(result.isFailure, "Expected getStreamUrl to fail and throw an exception")

        val exception = result.exceptionOrNull()
        assertNotNull(exception, "Exception should not be null")

        val expectedMessage = "No stream available across all fallback providers"
        assertTrue(
            exception?.message?.contains(expectedMessage) == true,
            "Exception message did not match. Actual message was: '${exception?.message}'"
        )
    }
}