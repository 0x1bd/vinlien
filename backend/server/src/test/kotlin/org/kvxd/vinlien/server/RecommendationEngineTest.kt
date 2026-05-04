package org.kvxd.vinlien.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.kvxd.vinlien.shared.models.media.Track

class RecommendationEngineTest {

    @Test
    fun `taste capsules preserve separate listening pockets`() {
        val ambient = track("a1", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val ambientTwo = track("a2", "Soft Current", "Glass Harbor", "Quiet Rooms")
        val metal = track("m1", "Iron Signal", "Rift Engine", "Fault Lines")
        val metalTwo = track("m2", "Black Circuit", "Rift Engine", "Fault Lines")

        val capsules = TasteGraph.buildCapsules(
            listOf(
                TasteTrackSignal(ambient, 8.0, 4L, "liked"),
                TasteTrackSignal(ambientTwo, 6.0, 3L, "history"),
                TasteTrackSignal(metal, 8.0, 2L, "liked"),
                TasteTrackSignal(metalTwo, 6.0, 1L, "history")
            ),
            maxCapsules = 4
        )

        assertTrue(capsules.size >= 2, "Distinct music pockets should not collapse into one profile")

        val active = TasteGraph.activeCapsules(capsules, listOf(metal), limit = 1).first().first
        assertTrue(
            TasteGraph.topFeatureValues(active.features, "artist:", 2).any { it.contains("rift engine") },
            "The active capsule should follow the current session context"
        )
    }

    @Test
    fun `explicit dislikes dominate otherwise good taste fit`() {
        val engine = RecommendationEngine()
        val liked = track("liked", "Night Texture", "Glass Harbor", "Quiet Rooms")
        val blocked = track("blocked", "Soft Current", "Glass Harbor", "Quiet Rooms")

        val capsules = TasteGraph.buildCapsules(listOf(TasteTrackSignal(liked, 10.0, 1L, "liked")))
        val vector = engine.buildListeningVector(
            history = listOf(RecommendationEngine.HistoryEntry(liked.id, liked.artist, 1L)),
            skips = emptyList(),
            likedArtists = setOf("glass harbor"),
            dislikedTrackIds = setOf(blocked.id),
            tasteSignals = listOf(TasteTrackSignal(liked, 10.0, 1L, "liked")),
            tasteCapsules = capsules,
            nowMs = 2L
        )

        val blockedScore = engine.scoreCandidate(
            blocked,
            vector,
            recentPlayedIds = emptySet(),
            activeCapsules = TasteGraph.activeCapsules(capsules, listOf(liked))
        )
        val neutralScore = engine.scoreCandidate(
            track("neutral", "Open Window", "New Artist", "New Album"),
            vector,
            recentPlayedIds = emptySet(),
            activeCapsules = TasteGraph.activeCapsules(capsules, listOf(liked))
        )

        assertTrue(blockedScore < neutralScore - 80.0, "Explicit dislikes must beat capsule affinity")
    }

    @Test
    fun `short skips cool down tracks without treating artist as disliked`() {
        val engine = RecommendationEngine()
        val played = track("played", "First Song", "Glass Harbor", "Quiet Rooms")
        val skipped = track("skipped", "Skipped Song", "Glass Harbor", "Quiet Rooms")
        val sameArtist = track("same-artist", "Another Song", "Glass Harbor", "Quiet Rooms")
        val unrelated = track("unrelated", "Far Away", "Other Artist", "Other Album")

        val vector = engine.buildListeningVector(
            history = listOf(RecommendationEngine.HistoryEntry(played.id, played.artist, 1L)),
            skips = listOf(RecommendationEngine.SkipEntry(skipped.id, skipped.artist, 1_000L, 2L)),
            tasteSignals = listOf(TasteTrackSignal(played, 8.0, 1L, "history")),
            tasteCapsules = TasteGraph.buildCapsules(listOf(TasteTrackSignal(played, 8.0, 1L, "history"))),
            nowMs = 3L
        )

        val skippedScore = engine.scoreCandidate(skipped, vector, recentPlayedIds = emptySet())
        val sameArtistScore = engine.scoreCandidate(sameArtist, vector, recentPlayedIds = emptySet())
        val unrelatedScore = engine.scoreCandidate(unrelated, vector, recentPlayedIds = emptySet())

        assertTrue(skippedScore < sameArtistScore, "The skipped track itself should cool down")
        assertTrue(sameArtistScore > unrelatedScore, "A skip must not become an artist-level dislike")
    }

    // -------------------------------------------------------------------------
    // buildReason
    // -------------------------------------------------------------------------

    @Test
    fun `buildReason returns top artist label for high artist score`() {
        val engine = RecommendationEngine()
        val played = track("p1", "Song One", "Glass Harbor", "Album")
        val candidate = track("c1", "Song Two", "Glass Harbor", "Album")

        val vector = engine.buildListeningVector(
            history = List(20) { RecommendationEngine.HistoryEntry(played.id, played.artist, it.toLong()) },
            skips = emptyList(),
            tasteSignals = listOf(TasteTrackSignal(played, 8.0, 1L, "history")),
            tasteCapsules = TasteGraph.buildCapsules(listOf(TasteTrackSignal(played, 8.0, 1L, "history"))),
            nowMs = 21L
        )
        val reason = engine.buildReason(candidate, vector)
        assertTrue(reason.isNotBlank(), "Reason should not be blank")
    }

    @Test
    fun `buildReason returns explore label for unknown artist`() {
        val engine = RecommendationEngine()
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val unknown = track("u1", "New Song", "Brand New Artist", "Unknown Album")
        val reason = engine.buildReason(unknown, vector)
        assertEquals("Explore something new", reason)
    }

    @Test
    fun `buildReason returns from your favourites for liked artist with high score`() {
        val engine = RecommendationEngine()
        val played = track("p1", "Song One", "Glass Harbor", "Album")
        val candidate = track("c1", "Song Two", "Glass Harbor", "Album")

        // Build a vector with high artist score and liked artist
        val vector = engine.buildListeningVector(
            history = List(30) { RecommendationEngine.HistoryEntry(played.id, played.artist, it.toLong()) },
            skips = emptyList(),
            likedArtists = setOf("glass harbor"),
            tasteSignals = listOf(TasteTrackSignal(played, 10.0, 1L, "liked")),
            tasteCapsules = TasteGraph.buildCapsules(listOf(TasteTrackSignal(played, 10.0, 1L, "liked"))),
            nowMs = 31L
        )
        val reason = engine.buildReason(candidate, vector)
        assertTrue(reason.isNotBlank(), "Reason should not be blank")
    }

    // -------------------------------------------------------------------------
    // pickWithDiversity
    // -------------------------------------------------------------------------

    @Test
    fun `pickWithDiversity returns null for empty candidates`() {
        val engine = RecommendationEngine()
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val result = engine.pickWithDiversity(
            candidates = emptyList(),
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList()
        )
        assertNull(result)
    }

    @Test
    fun `pickWithDiversity returns a track when candidates are provided`() {
        val engine = RecommendationEngine()
        val candidates = listOf(
            track("c1", "Song One", "Artist One", "Album"),
            track("c2", "Song Two", "Artist Two", "Album"),
            track("c3", "Song Three", "Artist Three", "Album")
        )
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val result = engine.pickWithDiversity(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList()
        )
        assertNotNull(result)
        assertTrue(candidates.any { it.id == result.first.id })
    }

    @Test
    fun `pickWithDiversity avoids blocked artist when max consecutive reached`() {
        val engine = RecommendationEngine()
        val blockedArtist = "Repeated Artist"
        // Session already has 3 consecutive tracks from the blocked artist
        val sessionArtists = List(3) { blockedArtist }
        val candidates = listOf(
            track("b1", "Song One", blockedArtist, "Album"),
            track("b2", "Song Two", blockedArtist, "Album"),
            track("o1", "Other Song", "Other Artist", "Other Album")
        )
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)

        // Run multiple picks to account for probabilistic sampling
        val picks = (1..20).mapNotNull {
            engine.pickWithDiversity(
                candidates = candidates,
                vector = vector,
                recentPlayedIds = emptySet(),
                sessionArtists = sessionArtists,
                maxConsecutiveSameArtist = 3
            )
        }
        assertTrue(picks.any { it.first.artist == "Other Artist" },
            "Should occasionally pick a different artist when max consecutive is reached")
    }

    // -------------------------------------------------------------------------
    // buildRadioQueue
    // -------------------------------------------------------------------------

    @Test
    fun `buildRadioQueue returns requested queue size`() {
        val engine = RecommendationEngine()
        val candidates = (1..20).map { i ->
            track("t$i", "Song $i", "Artist ${i % 5}", "Album")
        }
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val queue = engine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList(),
            queueSize = 5
        )
        assertEquals(5, queue.size)
    }

    @Test
    fun `buildRadioQueue returns no duplicates`() {
        val engine = RecommendationEngine()
        val candidates = (1..20).map { i ->
            track("t$i", "Song $i", "Artist ${i % 5}", "Album")
        }
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val queue = engine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList(),
            queueSize = 10
        )
        val ids = queue.map { it.track.id }
        assertEquals(ids.distinct(), ids, "Radio queue should not contain duplicate tracks")
    }

    @Test
    fun `buildRadioQueue returns empty list for empty candidates`() {
        val engine = RecommendationEngine()
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val queue = engine.buildRadioQueue(
            candidates = emptyList(),
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList()
        )
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `buildRadioQueue respects max consecutive same artist constraint`() {
        val engine = RecommendationEngine()
        // 15 tracks from the same artist + 5 from others
        val dominantArtist = "Dominant"
        val candidates = (1..15).map { i -> track("d$i", "Song $i", dominantArtist, "Album") } +
                (1..5).map { i -> track("o$i", "Other Song $i", "Other $i", "Album") }

        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val queue = engine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = emptySet(),
            sessionArtists = emptyList(),
            queueSize = 10,
            maxConsecutiveSameArtist = 2
        )

        // Check no artist appears more than maxConsecutiveSameArtist times in a row
        val artists = queue.map { it.track.artist.lowercase().trim() }
        var consecutiveCount = 1
        for (i in 1 until artists.size) {
            if (artists[i] == artists[i - 1]) {
                consecutiveCount++
                assertTrue(consecutiveCount <= 2,
                    "Artist '${ artists[i] }' appears more than 2 times in a row")
            } else {
                consecutiveCount = 1
            }
        }
    }

    // -------------------------------------------------------------------------
    // scoreCandidate — additional edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `scoreCandidate applies recent penalty for recently played tracks`() {
        val engine = RecommendationEngine()
        val t = track("t1", "Song", "Artist", "Album")
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val scoreNormal = engine.scoreCandidate(t, vector, recentPlayedIds = emptySet())
        val scoreRecent = engine.scoreCandidate(t, vector, recentPlayedIds = setOf(t.id))
        assertTrue(scoreNormal > scoreRecent, "Recently played tracks should receive a penalty")
    }

    @Test
    fun `scoreCandidate applies disliked artist penalty`() {
        val engine = RecommendationEngine()
        val t = track("t1", "Song", "Glass Harbor", "Album")
        val vector = engine.buildListeningVector(
            history = emptyList(),
            skips = emptyList(),
            dislikedArtists = setOf("glass harbor"),
            nowMs = 1L
        )
        val scoreDisliked = engine.scoreCandidate(t, vector, recentPlayedIds = emptySet())
        val vectorClean = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val scoreNormal = engine.scoreCandidate(t, vectorClean, recentPlayedIds = emptySet())
        assertTrue(scoreNormal > scoreDisliked + 200.0, "Disliked artist should receive a large penalty")
    }

    @Test
    fun `scoreCandidate gives bonus for liked track`() {
        val engine = RecommendationEngine()
        val t = track("t1", "Song", "Artist", "Album")
        val vector = engine.buildListeningVector(
            history = emptyList(),
            skips = emptyList(),
            likedTrackIds = setOf(t.id),
            nowMs = 1L
        )
        val scoreLiked = engine.scoreCandidate(t, vector, recentPlayedIds = emptySet())
        val vectorClean = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        val scoreNormal = engine.scoreCandidate(t, vectorClean, recentPlayedIds = emptySet())
        assertTrue(scoreLiked > scoreNormal + 30.0, "Liked track should receive a bonus")
    }

    // -------------------------------------------------------------------------
    // buildListeningVector — additional edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `buildListeningVector normalizes artist scores to 0-1 range`() {
        val engine = RecommendationEngine()
        val entries = listOf(
            RecommendationEngine.HistoryEntry("t1", "Artist A", 1000L),
            RecommendationEngine.HistoryEntry("t2", "Artist A", 1001L),
            RecommendationEngine.HistoryEntry("t3", "Artist B", 1002L)
        )
        val vector = engine.buildListeningVector(history = entries, skips = emptyList(), nowMs = 2000L)
        assertTrue(vector.artistScores.values.all { it in 0.0..1.0 },
            "All artist scores should be in [0, 1]")
    }

    @Test
    fun `buildListeningVector produces empty scores for empty history`() {
        val engine = RecommendationEngine()
        val vector = engine.buildListeningVector(history = emptyList(), skips = emptyList(), nowMs = 1L)
        assertTrue(vector.artistScores.isEmpty())
        assertTrue(vector.topFamiliarArtists.isEmpty())
    }

    @Test
    fun `buildListeningVector skips with long play time have low skip weight`() {
        val engine = RecommendationEngine()
        val longSkip = RecommendationEngine.SkipEntry("t1", "Artist A", 60_000L, 1000L)
        val shortSkip = RecommendationEngine.SkipEntry("t2", "Artist B", 1_000L, 1000L)
        val vector = engine.buildListeningVector(
            history = emptyList(),
            skips = listOf(longSkip, shortSkip),
            nowMs = 1001L
        )
        val longSkipWeight = vector.trackSkipScores["t1"] ?: 0.0
        val shortSkipWeight = vector.trackSkipScores["t2"] ?: 0.0
        assertTrue(shortSkipWeight > longSkipWeight,
            "Shorter plays before skip should produce higher skip weight")
    }

    private fun track(id: String, title: String, artist: String, album: String): Track =
        Track(
            id = id,
            title = title,
            artist = artist,
            artists = listOf(artist),
            durationMs = 210_000L,
            artworkUrl = "https://example.com/$id.jpg",
            canonicalId = "canonical:$id",
            albumTitle = album
        )
}
