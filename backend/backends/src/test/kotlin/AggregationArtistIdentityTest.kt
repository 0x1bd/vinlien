import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.Track

class AggregationArtistIdentityTest {

    @Test
    fun `artist info rejects near matching artist names`() = runTest {
        val engine = AggregationEngine(
            listOf(
                artistProvider(
                    info = listOf(ArtistInfo(name = "GReeeeN", bio = "Wrong artist bio", tags = emptyList()))
                )
            )
        )

        assertNull(engine.getArtistInfo("GReeeN"))
    }

    @Test
    fun `artist pages keep exact artist data separate from near matches`() = runTest {
        val engine = AggregationEngine(
            listOf(
                artistProvider(
                    info = listOf(
                        ArtistInfo(name = "GReeeeN", bio = "Wrong artist bio", tags = emptyList())
                    )
                ),
                artistProvider(
                    info = listOf(
                        ArtistInfo(name = "GReeeN", bio = "Correct artist bio", tags = listOf("reggae"))
                    ),
                    albums = listOf(
                        album("wrong-album", "GReeeeN"),
                        album("right-album", "GReeeN")
                    ),
                    tracks = listOf(
                        track("wrong-track", "GReeeeN"),
                        track("right-track", "GReeeN")
                    )
                )
            )
        )

        assertEquals("Correct artist bio", engine.getArtistInfo("GReeeN")?.bio)
        assertEquals(listOf("GReeeN"), engine.getArtistAlbums("GReeeN").map { it.artist })
        assertEquals(listOf("GReeeN"), engine.getArtistTopTracks("GReeeN").map { it.artist })
    }

    private fun artistProvider(
        info: List<ArtistInfo> = emptyList(),
        albums: List<Album> = emptyList(),
        tracks: List<Track> = emptyList()
    ): MusicProvider = object : MusicProvider {
        override val id = "fake"
        override val name = "Fake"
        override val capabilities = setOf(
            Capability.ARTIST_INFO,
            Capability.ARTIST_ALBUMS,
            Capability.ARTIST_TOP_TRACKS
        )

        override suspend fun getArtistInfo(name: String): ArtistInfo? = info.firstOrNull()
        override suspend fun getArtistAlbums(artist: String): List<Album> = albums
        override suspend fun getArtistTopTracks(artist: String): List<Track> = tracks
    }

    private fun album(id: String, artist: String): Album =
        Album(id = id, title = "Album $id", artist = artist, artworkUrl = null)

    private fun track(id: String, artist: String): Track =
        Track(
            id = id,
            title = "Song $id",
            artist = artist,
            artists = listOf(artist),
            durationMs = 180_000L
        )
}