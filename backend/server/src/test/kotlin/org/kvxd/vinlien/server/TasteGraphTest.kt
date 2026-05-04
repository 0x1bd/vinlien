package org.kvxd.vinlien.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.kvxd.vinlien.shared.models.media.Track

class TasteGraphTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        artists: List<String> = listOf(artist),
        durationMs: Long = 210_000L,
        artworkUrl: String? = null,
        lastFmUrl: String? = null
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        artists = artists,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        canonicalId = "canonical:$id",
        albumTitle = album,
        lastFmUrl = lastFmUrl
    )

    // -------------------------------------------------------------------------
    // extractFeatures
    // -------------------------------------------------------------------------

    @Test
    fun `extractFeatures includes primary artist key`() {
        val t = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val features = TasteGraph.extractFeatures(t)
        assertTrue(features.containsKey("artist:glass harbor"), "Expected artist feature key")
    }

    @Test
    fun `extractFeatures includes album key when album is set`() {
        val t = track("1", "Song", "Artist", album = "Great Album")
        val features = TasteGraph.extractFeatures(t)
        assertTrue(features.keys.any { it.startsWith("album:") }, "Expected album feature key")
    }

    @Test
    fun `extractFeatures includes duration bucket`() {
        val short = track("s", "Short", "Artist", durationMs = 60_000L)
        val standard = track("m", "Medium", "Artist", durationMs = 180_000L)
        val long = track("l", "Long", "Artist", durationMs = 300_000L)
        val extended = track("e", "Extended", "Artist", durationMs = 500_000L)

        assertTrue(TasteGraph.extractFeatures(short).containsKey("duration:short"))
        assertTrue(TasteGraph.extractFeatures(standard).containsKey("duration:standard"))
        assertTrue(TasteGraph.extractFeatures(long).containsKey("duration:long"))
        assertTrue(TasteGraph.extractFeatures(extended).containsKey("duration:extended"))
    }

    @Test
    fun `extractFeatures adds lastfm metadata flag`() {
        val t = track("1", "Song", "Artist", lastFmUrl = "https://last.fm/song")
        val features = TasteGraph.extractFeatures(t)
        assertTrue(features.containsKey("metadata:lastfm"))
    }

    @Test
    fun `extractFeatures adds artwork metadata flag for non-youtube artwork`() {
        val t = track("1", "Song", "Artist", artworkUrl = "https://example.com/art.jpg")
        val features = TasteGraph.extractFeatures(t)
        assertTrue(features.containsKey("metadata:artwork"))
    }

    @Test
    fun `extractFeatures does not add artwork flag for ytimg artwork`() {
        val t = track("1", "Song", "Artist", artworkUrl = "https://ytimg.com/vi/abc/hq.jpg")
        val features = TasteGraph.extractFeatures(t)
        assertTrue(!features.containsKey("metadata:artwork"))
    }

    @Test
    fun `extractFeatures respects MAX_FEATURES limit`() {
        // A track with many features should still be trimmed
        val t = track(
            "1", "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi",
            "One Two Three Four Five Six Seven Eight Nine Ten Eleven Twelve",
            album = "Album With Very Long Title That Has Many Tokens"
        )
        val features = TasteGraph.extractFeatures(t)
        assertTrue(features.size <= 36, "Features should be trimmed to MAX_FEATURES=36")
    }

    // -------------------------------------------------------------------------
    // primaryArtist
    // -------------------------------------------------------------------------

    @Test
    fun `primaryArtist uses first element of artists list`() {
        val t = track("1", "Song", "Glass Harbor & Echo", artists = listOf("Glass Harbor", "Echo"))
        assertEquals("glass harbor", TasteGraph.primaryArtist(t))
    }

    @Test
    fun `primaryArtist falls back to artist field when list is empty`() {
        val t = Track(
            id = "1", title = "Song", artist = "Glass Harbor feat. Echo",
            artists = emptyList(), durationMs = 0
        )
        assertEquals("glass harbor", TasteGraph.primaryArtist(t))
    }

    @Test
    fun `primaryArtist strips feat suffix from plain artist field`() {
        val t = Track(
            id = "1", title = "Song", artist = "Artist feat. Guest",
            artists = emptyList(), durationMs = 0
        )
        assertEquals("artist", TasteGraph.primaryArtist(t))
    }

    // -------------------------------------------------------------------------
    // centroid
    // -------------------------------------------------------------------------

    @Test
    fun `centroid of empty list is empty`() {
        assertEquals(emptyMap(), TasteGraph.centroid(emptyList()))
    }

    @Test
    fun `centroid merges feature weights`() {
        val a = mapOf("artist:foo" to 2.0, "token:bar" to 1.0)
        val b = mapOf("artist:foo" to 3.0, "token:baz" to 1.0)
        val result = TasteGraph.centroid(listOf(a, b))
        assertEquals(5.0, result["artist:foo"])
        assertEquals(1.0, result["token:bar"])
        assertEquals(1.0, result["token:baz"])
    }

    @Test
    fun `centroid trims non-positive values`() {
        val features = mapOf("positive" to 1.0, "zero" to 0.0, "negative" to -1.0)
        val result = TasteGraph.centroid(listOf(features))
        assertTrue(result.containsKey("positive"))
        assertTrue(!result.containsKey("zero"))
        assertTrue(!result.containsKey("negative"))
    }

    // -------------------------------------------------------------------------
    // topFeatureValues
    // -------------------------------------------------------------------------

    @Test
    fun `topFeatureValues returns sorted values for prefix`() {
        val features = mapOf("artist:foo" to 3.0, "artist:bar" to 5.0, "token:test" to 1.0)
        val top = TasteGraph.topFeatureValues(features, "artist:", 2)
        assertEquals(listOf("bar", "foo"), top)
    }

    @Test
    fun `topFeatureValues respects limit`() {
        val features = mapOf("artist:a" to 3.0, "artist:b" to 2.0, "artist:c" to 1.0)
        val top = TasteGraph.topFeatureValues(features, "artist:", 2)
        assertEquals(2, top.size)
    }

    @Test
    fun `topFeatureValues returns empty list when no matching prefix`() {
        val features = mapOf("token:foo" to 1.0)
        val top = TasteGraph.topFeatureValues(features, "artist:", 5)
        assertTrue(top.isEmpty())
    }

    // -------------------------------------------------------------------------
    // serializeFeatures / deserializeFeatures
    // -------------------------------------------------------------------------

    @Test
    fun `serialize and deserialize features round-trips correctly`() {
        val t = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val features = TasteGraph.extractFeatures(t)
        val serialized = TasteGraph.serializeFeatures(features)
        val deserialized = TasteGraph.deserializeFeatures(serialized)
        assertEquals(features, deserialized)
    }

    @Test
    fun `deserializeFeatures returns empty map for invalid JSON`() {
        val result = TasteGraph.deserializeFeatures("not-json")
        assertEquals(emptyMap(), result)
    }

    // -------------------------------------------------------------------------
    // capsuleFit
    // -------------------------------------------------------------------------

    @Test
    fun `capsuleFit returns 0 for empty capsule list`() {
        val t = track("1", "Song", "Artist")
        assertEquals(0.0, TasteGraph.capsuleFit(t, emptyList()))
    }

    @Test
    fun `capsuleFit is higher for same-artist capsule than unrelated one`() {
        val ambient = track("a1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val ambientTwo = track("a2", "Soft Current", "Glass Harbor", "Quiet Rooms")
        val metal = track("m1", "Iron Signal", "Rift Engine", "Fault Lines")

        val capsules = TasteGraph.buildCapsules(
            listOf(
                TasteTrackSignal(ambient, 8.0, 1L, "liked"),
                TasteTrackSignal(ambientTwo, 6.0, 2L, "liked")
            )
        )
        val activeCapsules = capsules.map { it to 1.0 }

        val ambientFit = TasteGraph.capsuleFit(
            track("a3", "Another Texture", "Glass Harbor", "Quiet Rooms"),
            activeCapsules
        )
        val metalFit = TasteGraph.capsuleFit(metal, activeCapsules)

        assertTrue(ambientFit > metalFit, "Same-artist capsule fit should be higher")
    }

    // -------------------------------------------------------------------------
    // featureSimilarity
    // -------------------------------------------------------------------------

    @Test
    fun `featureSimilarity of identical tracks is 1`() {
        val t = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val sim = TasteGraph.featureSimilarity(t, t)
        assertEquals(1.0, sim, 0.001)
    }

    @Test
    fun `featureSimilarity of completely different tracks is less than 1`() {
        val a = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val b = track("2", "Iron Signal", "Rift Engine", "Fault Lines")
        val sim = TasteGraph.featureSimilarity(a, b)
        assertTrue(sim < 1.0, "Unrelated tracks should have similarity < 1")
    }

    @Test
    fun `featureSimilarity is symmetric`() {
        val a = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val b = track("2", "Iron Signal", "Rift Engine", "Fault Lines")
        val ab = TasteGraph.featureSimilarity(a, b)
        val ba = TasteGraph.featureSimilarity(b, a)
        assertEquals(ab, ba, 0.0001)
    }

    // -------------------------------------------------------------------------
    // searchQueries
    // -------------------------------------------------------------------------

    @Test
    fun `searchQueries includes seed track artist`() {
        val seed = track("1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val capsule = TasteCapsuleModel(
            key = "taste-1",
            label = "Glass Harbor",
            features = mapOf("artist:glass harbor" to 5.0, "token:texture" to 1.0),
            weight = 10.0
        )
        val queries = TasteGraph.searchQueries(listOf(capsule to 1.0), seed, limit = 8)
        assertTrue(queries.any { it.contains("glass harbor", ignoreCase = true) },
            "Expected seed artist in queries")
    }

    @Test
    fun `searchQueries respects limit`() {
        val seed = track("1", "Night Texture", "Glass Harbor")
        val capsule = TasteCapsuleModel(
            key = "taste-1",
            label = "Glass Harbor",
            features = mapOf("artist:a" to 5.0, "artist:b" to 4.0, "artist:c" to 3.0,
                "token:x" to 2.0, "token:y" to 1.5, "album:z" to 1.0),
            weight = 10.0
        )
        val queries = TasteGraph.searchQueries(listOf(capsule to 1.0), seed, limit = 3)
        assertTrue(queries.size <= 3)
    }

    @Test
    fun `searchQueries returns no duplicates`() {
        val seed = track("1", "Night Texture", "Glass Harbor")
        val capsule = TasteCapsuleModel(
            key = "taste-1",
            label = "Glass Harbor",
            features = mapOf("artist:glass harbor" to 5.0),
            weight = 10.0
        )
        val queries = TasteGraph.searchQueries(listOf(capsule to 1.0), seed, limit = 8)
        assertEquals(queries.size, queries.distinctBy { it.lowercase() }.size, "Queries should be unique")
    }

    // -------------------------------------------------------------------------
    // activeCapsules
    // -------------------------------------------------------------------------

    @Test
    fun `activeCapsules returns top capsules when context is empty`() {
        val capsules = listOf(
            TasteCapsuleModel("c1", "Label1", mapOf("artist:foo" to 5.0), 10.0),
            TasteCapsuleModel("c2", "Label2", mapOf("artist:bar" to 3.0), 5.0),
            TasteCapsuleModel("c3", "Label3", mapOf("artist:baz" to 2.0), 2.0)
        )
        val active = TasteGraph.activeCapsules(capsules, emptyList(), limit = 2)
        assertEquals(2, active.size)
    }

    @Test
    fun `activeCapsules returns empty for empty capsule list`() {
        val active = TasteGraph.activeCapsules(emptyList(), emptyList())
        assertTrue(active.isEmpty())
    }

    @Test
    fun `activeCapsules scores context relevance`() {
        val glassHarborTrack = track("a1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val riftEngineTrack = track("m1", "Iron Signal", "Rift Engine", "Fault Lines")

        val capsules = TasteGraph.buildCapsules(
            listOf(
                TasteTrackSignal(glassHarborTrack, 8.0, 1L, "liked"),
                TasteTrackSignal(riftEngineTrack, 8.0, 2L, "liked")
            ),
            maxCapsules = 4
        )

        // Context is a Glass Harbor track — the Glass Harbor capsule should rank first
        val active = TasteGraph.activeCapsules(capsules, listOf(glassHarborTrack), limit = 1)
        val topArtist = TasteGraph.topFeatureValues(active.first().first.features, "artist:", 1).firstOrNull()
        assertEquals("glass harbor", topArtist)
    }
}
