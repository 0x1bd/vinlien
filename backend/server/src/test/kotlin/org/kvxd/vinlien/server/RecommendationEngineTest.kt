package org.kvxd.vinlien.server

import kotlin.test.Test
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
