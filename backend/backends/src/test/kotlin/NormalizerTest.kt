import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.Track

class NormalizerTest {

    // -------------------------------------------------------------------------
    // canonicalIdFor
    // -------------------------------------------------------------------------

    @Test
    fun `canonicalIdFor lowercases and trims both parts`() {
        assertEquals("glass harbor:::night texture", Normalizer.canonicalIdFor("Glass Harbor", "Night Texture"))
    }

    @Test
    fun `canonicalIdFor preserves separator`() {
        val id = Normalizer.canonicalIdFor("Artist", "Title")
        assertTrue(id.contains(":::"))
    }

    // -------------------------------------------------------------------------
    // primaryArtist
    // -------------------------------------------------------------------------

    @Test
    fun `primaryArtist returns first element of artists list`() {
        val track = track("1", "Song", "Glass Harbor & Echo", listOf("Glass Harbor", "Echo"))
        assertEquals("Glass Harbor", Normalizer.primaryArtist(track))
    }

    @Test
    fun `primaryArtist splits on comma when artists list is empty`() {
        val track = track("1", "Song", "Artist One, Artist Two")
        assertEquals("Artist One", Normalizer.primaryArtist(track))
    }

    @Test
    fun `primaryArtist splits on ampersand when artists list is empty`() {
        val track = track("1", "Song", "Artist One & Artist Two")
        assertEquals("Artist One", Normalizer.primaryArtist(track))
    }

    // -------------------------------------------------------------------------
    // normalizeTrack
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeTrack strips feat annotation from title`() {
        val t = track("1", "Song feat. Guest Artist", "Main Artist")
        val normalized = Normalizer.normalizeTrack(t)
        assertFalse(
            normalized.title.contains("feat", ignoreCase = true),
            "Normalized title should not contain feat: '${normalized.title}'"
        )
    }

    @Test
    fun `normalizeTrack moves featured artist from title to artist field`() {
        val t = track("1", "Song feat. Guest Artist", "Main Artist")
        val normalized = Normalizer.normalizeTrack(t)
        assertTrue(
            normalized.artist.contains("Guest Artist", ignoreCase = true),
            "Featured artist should appear in artist field: '${normalized.artist}'"
        )
    }

    @Test
    fun `normalizeTrack strips title noise suffixes`() {
        val t = track("1", "Song [Official Music Video]", "Artist")
        val normalized = Normalizer.normalizeTrack(t)
        assertFalse(
            normalized.title.contains("Official", ignoreCase = true),
            "Normalized title should strip noise suffix: '${normalized.title}'"
        )
    }

    @Test
    fun `normalizeTrack fixes all-caps title`() {
        val t = track("1", "NIGHT TEXTURE", "GLASS HARBOR")
        val normalized = Normalizer.normalizeTrack(t)
        // Should not be all-caps; at least the first letter of each word should be uppercase
        assertFalse(
            normalized.title == normalized.title.uppercase() && normalized.title.any { it.isLowerCase().not() && it.isLetter() },
            "All-caps title should be normalized: '${normalized.title}'"
        )
    }

    @Test
    fun `normalizeTrack splits artists field into artists list`() {
        val t = track("1", "Song", "Artist A & Artist B")
        val normalized = Normalizer.normalizeTrack(t)
        assertTrue(normalized.artists.size >= 2, "Multiple artists should be split: ${normalized.artists}")
    }

    @Test
    fun `normalizeTrack does not alter a clean track`() {
        val t = track("1", "Night Texture", "Glass Harbor")
        val normalized = Normalizer.normalizeTrack(t)
        assertEquals("Night Texture", normalized.title)
        assertEquals("Glass Harbor", normalized.artist)
    }

    @Test
    fun `normalizeTrack handles ft bracket variant`() {
        val t = track("1", "Song (ft. Guest)", "Artist")
        val normalized = Normalizer.normalizeTrack(t)
        assertFalse(
            normalized.title.contains("ft.", ignoreCase = true),
            "Should strip 'ft.' from title: '${normalized.title}'"
        )
    }

    // -------------------------------------------------------------------------
    // normalizeAlbum
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeAlbum fixes all-caps album title`() {
        val album = Album(
            id = "1",
            title = "QUIET ROOMS",
            artist = "GLASS HARBOR",
            artworkUrl = null,
            tracks = emptyList()
        )
        val normalized = Normalizer.normalizeAlbum(album)
        assertFalse(
            normalized.title == "QUIET ROOMS",
            "All-caps album title should be normalized: '${normalized.title}'"
        )
    }

    @Test
    fun `normalizeAlbum normalizes all tracks within the album`() {
        val track = track("1", "SONG [Official Audio]", "ARTIST")
        val album = Album(id = "a1", title = "Album", artist = "Artist", artworkUrl = null, tracks = listOf(track))
        val normalized = Normalizer.normalizeAlbum(album)
        assertFalse(
            normalized.tracks.first().title.contains("Official", ignoreCase = true),
            "Track in album should have noise removed: '${normalized.tracks.first().title}'"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun track(
        id: String,
        title: String,
        artist: String,
        artists: List<String> = emptyList()
    ): Track = Track(id = id, title = title, artist = artist, artists = artists, durationMs = 0)
}
