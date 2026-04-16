package org.kvxd.vinlien.server

import org.kvxd.vinlien.shared.models.media.Track
import kotlin.test.*

class RecommendationEngineTest {

    private val engine = RecommendationEngine()
    private val now = System.currentTimeMillis()

    private fun track(id: String, artist: String, title: String = "Song $id", canonicalId: String? = null) =
        Track(id = id, title = title, artist = artist, durationMs = 200_000L, canonicalId = canonicalId)

    private fun historyEntry(trackId: String, artist: String, agoMs: Long = 0L) =
        RecommendationEngine.HistoryEntry(trackId = trackId, artist = artist, timestampMs = now - agoMs)

    private fun skipEntry(trackId: String, artist: String, playedMs: Long) =
        RecommendationEngine.SkipEntry(trackId = trackId, artist = artist, playedMs = playedMs)

    @Test
    fun `buildListeningVector normalizes artist weights to 0-1`() {
        val history = listOf(
            historyEntry("t1", "Artist A"),
            historyEntry("t2", "Artist A"),
            historyEntry("t3", "Artist A"),
            historyEntry("t4", "Artist B")
        )
        val vector = engine.buildListeningVector(history, emptyList())

        assertEquals(1.0, vector.artistWeights["artist a"], "top artist should be 1.0")
        assertTrue(vector.artistWeights["artist b"]!! < 1.0, "lower-count artist < 1.0")
        assertTrue(vector.artistWeights["artist b"]!! > 0.0, "any played artist > 0.0")
    }

    @Test
    fun `buildListeningVector skips after 30s do not count as negative`() {
        val skips = listOf(
            skipEntry("t1", "Artist A", playedMs = 45_000L),
            skipEntry("t2", "Artist B", playedMs = 10_000L)
        )
        val vector = engine.buildListeningVector(emptyList(), skips)

        assertFalse("artist a" in vector.artistSkipCounts, "late skip should not count")
        assertEquals(1, vector.artistSkipCounts["artist b"])
        assertFalse("t1" in vector.skippedTrackIds)
        assertTrue("t2" in vector.skippedTrackIds)
    }

    @Test
    fun `buildListeningVector top artists ordered by weight descending`() {
        val history = buildList {
            repeat(10) { add(historyEntry("t${it}a", "Artist A")) }
            repeat(5) { add(historyEntry("t${it}b", "Artist B")) }
            repeat(1) { add(historyEntry("t${it}c", "Artist C")) }
        }
        val vector = engine.buildListeningVector(history, emptyList())

        assertEquals("artist a", vector.topArtists[0])
        assertEquals("artist b", vector.topArtists[1])
        assertEquals("artist c", vector.topArtists[2])
    }

    @Test
    fun `buildListeningVector ignores blank artist names`() {
        val history = listOf(historyEntry("t1", "  ", agoMs = 0L))
        val vector = engine.buildListeningVector(history, emptyList())

        assertTrue(vector.artistWeights.isEmpty())
    }

    @Test
    fun `scoreCandidate returns NEGATIVE_INFINITY for hard-skipped track`() {
        val vector = engine.buildListeningVector(
            emptyList(),
            listOf(skipEntry("skip1", "Artist X", 5_000L))
        )
        val score = engine.scoreCandidate(track("skip1", "Artist X"), vector, emptyList())
        assertEquals(Double.NEGATIVE_INFINITY, score)
    }

    @Test
    fun `scoreCandidate familiar artist scores higher than unknown artist`() {
        val history = List(10) { historyEntry("t$it", "Beloved Artist") }
        val vector = engine.buildListeningVector(history, emptyList())

        val familiarScore = engine.scoreCandidate(track("new1", "Beloved Artist"), vector, emptyList())
        val unknownScore = engine.scoreCandidate(track("new2", "Unknown Artist"), vector, emptyList())
        assertTrue(familiarScore > unknownScore)
    }

    @Test
    fun `scoreCandidate artist with skips scores lower than clean artist`() {
        val history = List(5) { historyEntry("t$it", "Bad Artist") } +
                List(5) { historyEntry("t${it + 5}", "Good Artist") }
        val skips = listOf(skipEntry("s1", "Bad Artist", 5_000L))
        val vector = engine.buildListeningVector(history, skips)

        val badScore = engine.scoreCandidate(track("x1", "Bad Artist"), vector, emptyList())
        val goodScore = engine.scoreCandidate(track("x2", "Good Artist"), vector, emptyList())
        assertTrue(badScore < goodScore, "skipped artist should score lower")
    }

    @Test
    fun `scoreCandidate recently played track scores lower than older one`() {
        val history = List(5) { historyEntry("t$it", "Artist A") }
        val vector = engine.buildListeningVector(history, emptyList())

        val recentIds = listOf("track-recent", "t1", "t2")
        val recentScore = engine.scoreCandidate(track("track-recent", "Artist A"), vector, recentIds)
        val olderScore = engine.scoreCandidate(track("track-old", "Artist A"), vector, emptyList())
        assertTrue(recentScore < olderScore, "recently played track should score lower")
    }

    @Test
    fun `scoreCandidate matches by canonicalId for recency penalty`() {
        val history = List(3) { historyEntry("t$it", "Artist A") }
        val vector = engine.buildListeningVector(history, emptyList())

        val recentIds = listOf("canonical-123")
        val trackWithCanonical = track("provider-xyz", "Artist A").copy(canonicalId = "canonical-123")
        val trackWithoutCanonical = track("provider-abc", "Artist A")

        val penalizedScore = engine.scoreCandidate(trackWithCanonical, vector, recentIds)
        val normalScore = engine.scoreCandidate(trackWithoutCanonical, vector, recentIds)
        assertTrue(penalizedScore < normalScore)
    }

    @Test
    fun `buildReason returns artist reason for high-weight artist`() {
        val history = List(10) { historyEntry("t$it", "Radiohead") }
        val vector = engine.buildListeningVector(history, emptyList())
        val seed = track("seed", "Portishead", "Glory Box")

        val reason = engine.buildReason(track("r1", "Radiohead"), vector, listOf(seed))
        assertTrue(reason.contains("Radiohead"), "reason should mention the familiar artist")
        assertTrue(reason.startsWith("Because you like"), reason)
    }

    @Test
    fun `buildReason returns seed track reason for medium-weight artist`() {
        val history = List(4) { historyEntry("t$it", "Modest Mouse") } +
                List(10) { historyEntry("t${it + 4}", "Top Artist") }
        val vector = engine.buildListeningVector(history, emptyList())
        val seed = track("seed", "Top Artist", "Float On")

        val reason = engine.buildReason(track("r1", "Modest Mouse"), vector, listOf(seed))
        assertTrue(reason.startsWith("You listen to"), reason)
        assertTrue(reason.contains("Modest Mouse"), reason)
    }

    @Test
    fun `buildReason returns discover reason for unknown artist`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val seed = track("seed", "Known", "Known Song")

        val reason = engine.buildReason(track("r1", "Brand New Band"), vector, listOf(seed))
        assertTrue(reason.startsWith("Discover"), reason)
        assertTrue(reason.contains("Brand New Band"), reason)
    }

    @Test
    fun `pickWithDiversity returns null for empty candidates`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val result = engine.pickWithDiversity(emptyList(), vector, listOf(track("s", "X")), emptyList(), emptyList())
        assertNull(result)
    }

    @Test
    fun `pickWithDiversity excludes hard-skipped tracks`() {
        val skips = listOf(skipEntry("bad-track", "Artist A", 3_000L))
        val vector = engine.buildListeningVector(emptyList(), skips)

        val candidates = listOf(track("bad-track", "Artist A"), track("good-track", "Artist B"))
        val result = engine.pickWithDiversity(candidates, vector, listOf(track("s", "X")), emptyList(), emptyList())

        assertNotNull(result)
        assertEquals("good-track", result.first.id)
    }

    @Test
    fun `pickWithDiversity enforces max consecutive same artist`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val seed = track("s", "Seed")

        val sessionArtists = listOf("artist a", "artist a", "artist a")
        val candidates = listOf(
            track("t1", "Artist A"),
            track("t2", "Artist B"),
            track("t3", "Artist C")
        )

        val result = engine.pickWithDiversity(
            candidates, vector, listOf(seed), emptyList(), sessionArtists,
            maxConsecutiveSameArtist = 3
        )

        assertNotNull(result)
        assertNotEquals("artist a", result.first.artist.lowercase(), "must not pick same artist again")
    }

    @Test
    fun `pickWithDiversity enforces novelty budget when familiar artists dominate session`() {
        val history = List(20) { historyEntry("t$it", "Famous Artist") }
        val vector = engine.buildListeningVector(history, emptyList())

        val sessionArtists = List(5) { "famous artist" }
        val candidates = listOf(
            track("t1", "Famous Artist"),
            track("t2", "Fresh New Artist")
        )

        val result = engine.pickWithDiversity(
            candidates, vector, listOf(track("s", "seed")), emptyList(), sessionArtists,
            noveltyBudget = 0.30f
        )

        assertNotNull(result)
        assertEquals("t2", result.first.id, "should pick unfamiliar artist to satisfy novelty budget")
    }

    @Test
    fun `pickWithDiversity falls back to best when constraint yields no matches`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val sessionArtists = listOf("only artist", "only artist", "only artist")
        val candidates = listOf(track("t1", "Only Artist"))

        val result = engine.pickWithDiversity(
            candidates, vector, listOf(track("s", "seed")), emptyList(), sessionArtists,
            maxConsecutiveSameArtist = 3
        )

        assertNotNull(result, "should fall back rather than return null")
        assertEquals("t1", result.first.id)
    }

    @Test
    fun `buildRadioQueue returns at most queueSize tracks`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val candidates = List(20) { track("t$it", "Artist ${it % 5}") }

        val queue = engine.buildRadioQueue(candidates, vector, listOf(track("s", "Seed")), emptyList(), emptyList(), queueSize = 5)

        assertEquals(5, queue.size)
    }

    @Test
    fun `buildRadioQueue does not repeat the same track`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val candidates = List(10) { track("t$it", "Artist ${it % 3}") }

        val queue = engine.buildRadioQueue(candidates, vector, listOf(track("s", "Seed")), emptyList(), emptyList(), queueSize = 10)

        val ids = queue.map { it.track.id }
        assertEquals(ids.distinct(), ids, "each track should appear at most once")
    }

    @Test
    fun `buildRadioQueue enforces diversity across all picks`() {
        val history = List(30) { historyEntry("h$it", "Mega Popular Artist") }
        val vector = engine.buildListeningVector(history, emptyList())

        val candidates = List(10) { track("p$it", "Mega Popular Artist") } +
                List(10) { track("u$it", "Unknown Artist $it") }

        val queue = engine.buildRadioQueue(
            candidates, vector, listOf(track("s", "Seed")), emptyList(), emptyList(),
            queueSize = 10, noveltyBudget = 0.30f
        )

        val unknownCount = queue.count { it.track.artist.startsWith("Unknown") }
        assertTrue(unknownCount >= 2, "at least ~30% should be unfamiliar — got $unknownCount/10")
    }

    @Test
    fun `buildRadioQueue returns fewer tracks than queueSize when candidates exhausted`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val candidates = List(3) { track("t$it", "Artist $it") }

        val queue = engine.buildRadioQueue(candidates, vector, listOf(track("s", "Seed")), emptyList(), emptyList(), queueSize = 10)

        assertTrue(queue.size <= 3)
    }

    @Test
    fun `shouldReseed is false at zero tracks`() {
        assertFalse(engine.shouldReseed(0))
    }

    @Test
    fun `shouldReseed triggers at exact reseedInterval`() {
        assertTrue(engine.shouldReseed(5, reseedInterval = 5))
        assertTrue(engine.shouldReseed(10, reseedInterval = 5))
        assertFalse(engine.shouldReseed(4, reseedInterval = 5))
        assertFalse(engine.shouldReseed(6, reseedInterval = 5))
    }

    @Test
    fun `shouldReseed respects custom interval`() {
        assertTrue(engine.shouldReseed(3, reseedInterval = 3))
        assertTrue(engine.shouldReseed(6, reseedInterval = 3))
        assertFalse(engine.shouldReseed(4, reseedInterval = 3))
    }

    @Test
    fun `pickReseedTrack prefers different artist than current seed`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val currentSeed = track("seed", "Artist A")
        val candidates = listOf(
            track("t1", "Artist A"),
            track("t2", "Artist B"),
            track("t3", "Artist C")
        )

        val newSeed = engine.pickReseedTrack(candidates, currentSeed, vector)
        assertNotNull(newSeed)
        assertNotEquals("artist a", newSeed.artist.lowercase(), "should pick different artist")
    }

    @Test
    fun `pickReseedTrack returns null for empty candidates`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        assertNull(engine.pickReseedTrack(emptyList(), track("s", "X"), vector))
    }

    @Test
    fun `pickReseedTrack falls back to same-artist candidate when no alternatives exist`() {
        val vector = engine.buildListeningVector(emptyList(), emptyList())
        val currentSeed = track("seed", "Only Artist")
        val candidates = listOf(track("t1", "Only Artist"))

        val result = engine.pickReseedTrack(candidates, currentSeed, vector)
        assertNotNull(result)
    }

    @Test
    fun `pickReseedTrack excludes hard-skipped tracks`() {
        val skips = listOf(skipEntry("t2", "Artist B", 2_000L))
        val vector = engine.buildListeningVector(emptyList(), skips)
        val currentSeed = track("seed", "Artist A")
        val candidates = listOf(track("t2", "Artist B"), track("t3", "Artist C"))

        val result = engine.pickReseedTrack(candidates, currentSeed, vector)
        assertNotNull(result)
        assertEquals("t3", result.id, "skipped track should be excluded from reseed candidates")
    }
}
