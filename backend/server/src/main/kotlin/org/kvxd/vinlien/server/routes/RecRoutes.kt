package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.Config
import org.kvxd.vinlien.server.RecommendationEngine
import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.services.RecService
import org.kvxd.vinlien.shared.models.feed.RadioRequest
import org.kvxd.vinlien.shared.models.feed.RadioResponse
import org.kvxd.vinlien.shared.models.feed.RecRequest
import org.kvxd.vinlien.shared.models.feed.RecResult
import org.kvxd.vinlien.shared.models.feed.SkipRequest
import org.kvxd.vinlien.shared.models.media.Track

private val recEngine = RecommendationEngine()

fun Route.recRoutes(engine: AggregationEngine) {

    post("/api/rec") {
        val req = call.receive<RecRequest>()
        val currentTrack = req.queue.lastOrNull()
            ?: return@post call.respond(HttpStatusCode.NoContent)

        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.buildRecProfile(userId, req.queue.map { it.title })
        val vector = recEngine.buildListeningVector(profile.history, profile.skips, Config.data.recDecayDays)

        val candidates = engine.getRecommendations(currentTrack)
            .filter { t -> req.queue.none { it.id == t.id } }
            .filter { t ->
                val fp = TrackFingerprint.of(t.title)
                fp.isBlank() || fp !in profile.seenFingerprints
            }

        val result = recEngine.pickWithDiversity(
            candidates = candidates,
            vector = vector,
            seeds = listOf(currentTrack),
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            decayDays = Config.data.recDecayDays,
            noveltyBudget = req.noveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
        )

        if (result != null) {
            call.respond(RecResult(result.first, result.second))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/api/rec/skip") {
        val req = call.receive<SkipRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        RecService.recordSkip(userId, req.trackId, req.artist, req.playedMs)
        call.respond(HttpStatusCode.OK)
    }

    post("/api/radio") {
        val req = call.receive<RadioRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.buildBaseProfile(userId)
        val vector = recEngine.buildListeningVector(profile.history, profile.skips, Config.data.recDecayDays)

        val allSeeds = (listOf(req.seedTrack) + req.additionalSeeds)
            .distinctBy { it.artist.lowercase().trim() }
            .take(4)

        val initialCandidates = engine.getRecommendations(req.seedTrack)
        val effectiveSeed = if (recEngine.shouldReseed(req.tracksPlayedInSession, Config.data.radioReseedInterval)) {
            recEngine.pickReseedTrack(initialCandidates, req.seedTrack, vector) ?: req.seedTrack
        } else {
            req.seedTrack
        }

        val effectiveSeeds = if (effectiveSeed.id != req.seedTrack.id) {
            listOf(effectiveSeed) + allSeeds.drop(1)
        } else {
            allSeeds
        }

        val candidates: List<Track> = coroutineScope {
            effectiveSeeds.map { seed ->
                async {
                    if (seed.id == effectiveSeed.id) initialCandidates
                    else runCatching { engine.getRecommendations(seed) }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }.distinctBy { it.id }
            .filter { t -> req.queue.none { it.id == t.id } }

        val queueSize = req.queueSize.coerceIn(1, 50)
        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            seeds = effectiveSeeds,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            queueSize = queueSize,
            decayDays = Config.data.recDecayDays,
            noveltyBudget = Config.data.recNoveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = effectiveSeed))
    }
}
