package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.Config
import org.kvxd.vinlien.server.RecommendationEngine
import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.normArtist
import org.kvxd.vinlien.server.services.RecService
import org.kvxd.vinlien.shared.models.feed.RadioRequest
import org.kvxd.vinlien.shared.models.feed.RadioResponse
import org.kvxd.vinlien.shared.models.feed.RecRequest
import org.kvxd.vinlien.shared.models.feed.RecResult
import org.kvxd.vinlien.shared.models.feed.SkipRequest
import org.kvxd.vinlien.shared.models.feed.PlayEventRequest
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class HomeRecRequest(val queueSize: Int = 4, val noveltyBudget: Float = 0.5f)

fun Route.recRoutes(engine: AggregationEngine, recEngine: RecommendationEngine) {

    post("/api/rec") {
        val req = call.receive<RecRequest>()
        val currentTrack = req.queue.lastOrNull()
            ?: return@post call.respond(HttpStatusCode.NoContent)
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId, req.queue.map { it.title })
        val vector = recEngine.buildListeningVector(
            profile.history, profile.skips, profile.likedArtists,
            likedTrackIds = profile.likedTrackIds,
            dislikedTrackIds = profile.dislikedTrackIds,
            dislikedArtists = profile.dislikedArtists,
            tasteSignals = profile.tasteSignals,
            tasteCapsules = profile.tasteCapsules,
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val contextTracks = req.queue.takeLast(12)
        val tasteSeeds = RecService.representativeTasteSeeds(profile, contextTracks + currentTrack, count = 4)
        val tasteQueries = RecService.tasteSearchQueries(profile, contextTracks, currentTrack, limit = 5)
        val candidates = candidatePool(engine, listOf(currentTrack) + tasteSeeds, tasteQueries).filterForRec(profile, req.queue)

        val result = recEngine.pickWithDiversity(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            noveltyBudget = req.noveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist,
            contextTracks = contextTracks
        )

        if (result != null) call.respond(RecResult(result.first, result.second))
        else call.respond(HttpStatusCode.NoContent)
    }

    post("/api/rec/skip") {
        val req = call.receive<SkipRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        RecService.recordSkip(userId, req.trackId, req.artist, req.playedMs)
        call.respond(HttpStatusCode.OK)
    }

    post("/api/rec/play-event") {
        val req = call.receive<PlayEventRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        RecService.recordPlayEvent(userId, req)
        call.respond(HttpStatusCode.OK)
    }

    post("/api/radio") {
        val req = call.receive<RadioRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId, req.queue.map { it.title })
        val vector = recEngine.buildListeningVector(
            profile.history, profile.skips, profile.likedArtists,
            likedTrackIds = profile.likedTrackIds,
            dislikedTrackIds = profile.dislikedTrackIds,
            dislikedArtists = profile.dislikedArtists,
            tasteSignals = profile.tasteSignals,
            tasteCapsules = profile.tasteCapsules,
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val allSeeds = (listOf(req.seedTrack) + req.additionalSeeds)
            .distinctBy { it.artist.normArtist() }
            .take(6)
        val contextTracks = (req.queue + listOf(req.seedTrack)).takeLast(16)
        val tasteSeeds = RecService.representativeTasteSeeds(profile, contextTracks, count = 6)
        val tasteQueries = RecService.tasteSearchQueries(profile, contextTracks, req.seedTrack, limit = 8)

        val candidates = candidatePool(engine, allSeeds + tasteSeeds, tasteQueries).filterForRec(profile, req.queue)

        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            queueSize = req.queueSize.coerceIn(1, 50),
            noveltyBudget = req.noveltyBudget ?: Config.data.recNoveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist,
            contextTracks = contextTracks
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = req.seedTrack))
    }

    post("/api/rec/similar") {
        val req = call.receive<RadioRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId)
        val candidates = engine.getRecommendations(req.seedTrack)
        val filtered = candidates.filterForRec(profile, req.queue).take(req.queueSize.coerceIn(1, 50))

        val resultTracks = filtered.map { RecResult(it, "Similar to ${req.seedTrack.artist}") }
        call.respond(RadioResponse(tracks = resultTracks, seedTrack = req.seedTrack))
    }

    post("/api/rec/home") {
        val req = call.receive<HomeRecRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId)
        if (profile.history.isEmpty()) return@post call.respond(HttpStatusCode.NoContent)

        val vector = recEngine.buildListeningVector(
            profile.history, profile.skips, profile.likedArtists,
            likedTrackIds = profile.likedTrackIds,
            dislikedTrackIds = profile.dislikedTrackIds,
            dislikedArtists = profile.dislikedArtists,
            tasteSignals = profile.tasteSignals,
            tasteCapsules = profile.tasteCapsules,
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val seeds = RecService.buildHomeSeeds(profile.history, vector, count = 6)
        if (seeds.isEmpty()) return@post call.respond(HttpStatusCode.NoContent)
        val tasteSeeds = RecService.representativeTasteSeeds(profile, seeds, count = 6)
        val tasteQueries = RecService.tasteSearchQueries(profile, seeds, seeds.first(), limit = 8)

        val candidates = candidatePool(engine, seeds + tasteSeeds, tasteQueries).filterForRec(profile, emptyList())

        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = emptyList(),
            queueSize = req.queueSize.coerceIn(1, 20),
            noveltyBudget = req.noveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist,
            contextTracks = seeds
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = seeds.first()))
    }
}

private suspend fun candidatePool(
    engine: AggregationEngine,
    seeds: List<Track>,
    queries: List<String>
): List<Track> = coroutineScope {
    val seedJobs = seeds
        .distinctBy { it.canonicalId ?: it.id }
        .take(10)
        .map { seed -> async { runCatching { engine.getRecommendations(seed) }.getOrDefault(emptyList()) } }

    val queryJobs = queries
        .take(10)
        .map { query -> async { runCatching { engine.searchTracks(query).take(18) }.getOrDefault(emptyList()) } }

    (seedJobs + queryJobs).flatMap { it.await() }
}

private fun List<Track>.filterForRec(profile: RecService.UserProfile, queue: List<Track>): List<Track> {
    val queueIds = queue.flatMap { listOfNotNull(it.id, it.canonicalId) }.toSet()
    return this
        .filter { t -> listOfNotNull(t.id, t.canonicalId).none { it in queueIds } }
        .filter { t -> listOfNotNull(t.id, t.canonicalId).none { it in profile.dislikedTrackIds } }
        .filter { t -> t.artist.normArtist() !in profile.dislikedArtists }
        .filter { t ->
            val fp = TrackFingerprint.of(t.title)
            fp.isBlank() || fp !in profile.seenFingerprints
        }
        .sortedBy { it.title.length }
        .distinctBy { TrackFingerprint.of(it.title) + "|" + it.artist.normArtist() }
}
