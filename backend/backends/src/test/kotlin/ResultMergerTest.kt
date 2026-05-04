import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.AlbumMerger
import org.kvxd.vinlien.backends.ArtistInfoMerger
import org.kvxd.vinlien.backends.TrackMerger
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.Track

class ResultMergerTest {

    // =========================================================================
    // TrackMerger
    // =========================================================================

    @Test
    fun `TrackMerger merge deduplicates same track from different providers`() {
        val track1 = track("sc:001", "Night Texture", "Glass Harbor", artworkUrl = "https://example.com/art.jpg")
        val track2 = track("itunes:001", "Night Texture", "Glass Harbor")
        val merged = TrackMerger.merge(listOf(track1, track2))
        assertEquals(1, merged.size, "Same track from two providers should merge into one")
    }

    @Test
    fun `TrackMerger merge keeps distinct tracks`() {
        val track1 = track("1", "Night Texture", "Glass Harbor")
        val track2 = track("2", "Iron Signal", "Rift Engine")
        val merged = TrackMerger.merge(listOf(track1, track2))
        assertEquals(2, merged.size)
    }

    @Test
    fun `TrackMerger merge picks best artwork from group`() {
        val withArt = track("1", "Song", "Artist", artworkUrl = "https://example.com/art.jpg")
        val withoutArt = track("2", "Song", "Artist", artworkUrl = null)
        val merged = TrackMerger.merge(listOf(withoutArt, withArt))
        assertEquals(1, merged.size)
        assertNotNull(merged.first().artworkUrl)
    }

    @Test
    fun `TrackMerger merge picks non-zero duration`() {
        val withDuration = track("1", "Song", "Artist", durationMs = 210_000L)
        val zeroDuration = track("2", "Song", "Artist", durationMs = 0L)
        val merged = TrackMerger.merge(listOf(zeroDuration, withDuration))
        assertEquals(1, merged.size)
        assertEquals(210_000L, merged.first().durationMs)
    }

    @Test
    fun `TrackMerger merge picks highest popularity score`() {
        val low = track("1", "Song", "Artist", popularityScore = 10.0)
        val high = track("2", "Song", "Artist", popularityScore = 90.0)
        val merged = TrackMerger.merge(listOf(low, high))
        assertEquals(1, merged.size)
        assertEquals(90.0, merged.first().popularityScore)
    }

    @Test
    fun `TrackMerger merge handles empty list`() {
        val merged = TrackMerger.merge(emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `TrackMerger merge handles single track`() {
        val t = track("1", "Song", "Artist")
        val merged = TrackMerger.merge(listOf(t))
        assertEquals(1, merged.size)
    }

    @Test
    fun `TrackMerger merge is case-insensitive for fingerprinting`() {
        val upper = track("1", "NIGHT TEXTURE", "GLASS HARBOR")
        val lower = track("2", "night texture", "glass harbor")
        val merged = TrackMerger.merge(listOf(upper, lower))
        assertEquals(1, merged.size, "Case difference should not prevent merge")
    }

    // =========================================================================
    // AlbumMerger.dedup
    // =========================================================================

    @Test
    fun `AlbumMerger dedup deduplicates same album`() {
        val a1 = album("lastfm:album:Glass Harbor:::Quiet Rooms", "Quiet Rooms", "Glass Harbor")
        val a2 = album("itunes:album:1:::Glass Harbor:::Quiet Rooms", "Quiet Rooms", "Glass Harbor")
        val deduped = AlbumMerger.dedup(listOf(a1, a2))
        assertEquals(1, deduped.size, "Same album from two sources should deduplicate to one")
    }

    @Test
    fun `AlbumMerger dedup keeps distinct albums`() {
        val a1 = album("1", "Quiet Rooms", "Glass Harbor")
        val a2 = album("2", "Fault Lines", "Rift Engine")
        val deduped = AlbumMerger.dedup(listOf(a1, a2))
        assertEquals(2, deduped.size)
    }

    @Test
    fun `AlbumMerger dedup handles empty list`() {
        assertTrue(AlbumMerger.dedup(emptyList()).isEmpty())
    }

    // =========================================================================
    // AlbumMerger.parseNativeId
    // =========================================================================

    @Test
    fun `parseNativeId returns null for unrecognized prefix`() {
        assertNull(AlbumMerger.parseNativeId("unknown:album:foo:::bar"))
    }

    @Test
    fun `parseNativeId parses merged album id`() {
        val (artist, title) = AlbumMerger.parseNativeId("merged:album:Glass Harbor:::Quiet Rooms") ?: fail("Should parse")
        assertEquals("Glass Harbor", artist)
        assertEquals("Quiet Rooms", title)
    }

    @Test
    fun `parseNativeId parses lastfm album id`() {
        val (artist, title) = AlbumMerger.parseNativeId("lastfm:album:Glass Harbor:::Quiet Rooms") ?: fail("Should parse")
        assertEquals("Glass Harbor", artist)
        assertEquals("Quiet Rooms", title)
    }

    @Test
    fun `parseNativeId parses itunes album id`() {
        val (artist, title) = AlbumMerger.parseNativeId("itunes:album:12345:::Glass Harbor:::Quiet Rooms") ?: fail("Should parse")
        assertEquals("Glass Harbor", artist)
        assertEquals("Quiet Rooms", title)
    }

    @Test
    fun `parseNativeId parses musicbrainz album id`() {
        val (artist, title) = AlbumMerger.parseNativeId("mb:album:Glass Harbor:::Quiet Rooms") ?: fail("Should parse")
        assertEquals("Glass Harbor", artist)
        assertEquals("Quiet Rooms", title)
    }

    @Test
    fun `parseNativeId parses deezer album id`() {
        val (artist, title) = AlbumMerger.parseNativeId("deezer:album:99999:::Glass Harbor:::Quiet Rooms") ?: fail("Should parse")
        assertEquals("Glass Harbor", artist)
        assertEquals("Quiet Rooms", title)
    }

    @Test
    fun `parseNativeId returns null for malformed merged id`() {
        // Only one part after the prefix (missing the separator)
        assertNull(AlbumMerger.parseNativeId("merged:album:NoSeparatorHere"))
    }

    // =========================================================================
    // AlbumMerger.mergeOne
    // =========================================================================

    @Test
    fun `mergeOne returns null for empty album list`() {
        assertNull(AlbumMerger.mergeOne(emptyList(), "lastfm:album:Artist:::Title"))
    }

    @Test
    fun `mergeOne picks best artwork from all albums`() {
        val withArt = album("1", "Quiet Rooms", "Glass Harbor", artworkUrl = "https://example.com/art.jpg")
        val withoutArt = album("2", "Quiet Rooms", "Glass Harbor", artworkUrl = null)
        val nativeId = "lastfm:album:Glass Harbor:::Quiet Rooms"
        val merged = AlbumMerger.mergeOne(listOf(withoutArt, withArt), nativeId)
        assertNotNull(merged?.artworkUrl)
    }

    @Test
    fun `mergeOne picks year from available sources`() {
        val withYear = album("1", "Quiet Rooms", "Glass Harbor", year = 2022)
        val withoutYear = album("2", "Quiet Rooms", "Glass Harbor")
        val nativeId = "lastfm:album:Glass Harbor:::Quiet Rooms"
        val merged = AlbumMerger.mergeOne(listOf(withoutYear, withYear), nativeId)
        assertEquals(2022, merged?.year)
    }

    // =========================================================================
    // ArtistInfoMerger
    // =========================================================================

    @Test
    fun `mergeOne returns null for empty artist info list`() {
        assertNull(ArtistInfoMerger.mergeOne(emptyList()))
    }

    @Test
    fun `mergeOne picks longest bio`() {
        val shortBio = ArtistInfo("Artist", "Short bio.", emptyList(), null)
        val longBio = ArtistInfo("Artist", "A much longer and more detailed biography.", emptyList(), null)
        val merged = ArtistInfoMerger.mergeOne(listOf(shortBio, longBio))
        assertEquals(longBio.bio, merged?.bio)
    }

    @Test
    fun `mergeOne picks first available image URL`() {
        val noImage = ArtistInfo("Artist", "bio", emptyList(), null)
        val withImage = ArtistInfo("Artist", "bio", emptyList(), "https://example.com/image.jpg")
        val merged = ArtistInfoMerger.mergeOne(listOf(noImage, withImage))
        assertEquals("https://example.com/image.jpg", merged?.imageUrl)
    }

    @Test
    fun `mergeOne merges and deduplicates tags`() {
        val info1 = ArtistInfo("Artist", "bio", listOf("rock", "indie"), null)
        val info2 = ArtistInfo("Artist", "bio", listOf("indie", "alternative"), null)
        val merged = ArtistInfoMerger.mergeOne(listOf(info1, info2))
        val tags = merged?.tags ?: emptyList()
        assertEquals(tags.distinct(), tags, "Tags should be deduplicated")
        assertTrue(tags.containsAll(listOf("rock", "indie", "alternative")))
    }

    @Test
    fun `mergeOne limits tags to 5`() {
        val info = ArtistInfo("Artist", "bio", listOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7"), null)
        val merged = ArtistInfoMerger.mergeOne(listOf(info))
        assertTrue((merged?.tags?.size ?: 0) <= 5, "Tags should be limited to 5")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        artworkUrl: String? = null,
        lastFmUrl: String? = null,
        popularityScore: Double? = null
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        artists = listOf(artist),
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        lastFmUrl = lastFmUrl,
        popularityScore = popularityScore
    )

    private fun album(
        id: String,
        title: String,
        artist: String,
        artworkUrl: String? = null,
        year: Int? = null,
        tracks: List<Track> = emptyList()
    ): Album = Album(id = id, title = title, artist = artist, artworkUrl = artworkUrl, year = year, tracks = tracks)
}
