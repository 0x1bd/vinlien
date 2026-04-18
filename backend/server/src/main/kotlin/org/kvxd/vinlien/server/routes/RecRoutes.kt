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
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val candidates = engine.getRecommendations(currentTrack).filterForRec(profile, req.queue)

        val result = recEngine.pickWithDiversity(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            noveltyBudget = req.noveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
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

    post("/api/radio") {
        val req = call.receive<RadioRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId)
        val vector = recEngine.buildListeningVector(
            profile.history, profile.skips, profile.likedArtists,
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val allSeeds = (listOf(req.seedTrack) + req.additionalSeeds)
            .distinctBy { it.artist.normArtist() }
            .take(6)

        val candidates: List<Track> = coroutineScope {
            allSeeds.map { seed ->
                async { runCatching { engine.getRecommendations(seed) }.getOrDefault(emptyList()) }
            }.flatMap { it.await() }
        }.filterForRec(profile, req.queue)

        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = req.sessionArtists,
            queueSize = req.queueSize.coerceIn(1, 50),
            noveltyBudget = req.noveltyBudget ?: Config.data.recNoveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = req.seedTrack))
    }

    post("/api/rec/home") {
        val req = call.receive<HomeRecRequest>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val profile = RecService.getProfile(userId)
        if (profile.history.isEmpty()) return@post call.respond(HttpStatusCode.NoContent)

        val vector = recEngine.buildListeningVector(
            profile.history, profile.skips, profile.likedArtists,
            halfLifeDays = Config.data.recHalfLifeDays
        )

        val seeds = RecService.buildHomeSeeds(profile.history, vector, count = 6)
        if (seeds.isEmpty()) return@post call.respond(HttpStatusCode.NoContent)

        val candidates: List<Track> = coroutineScope {
            seeds.map { seed ->
                async { runCatching { engine.getRecommendations(seed) }.getOrDefault(emptyList()) }
            }.flatMap { it.await() }
        }.filterForRec(profile, emptyList())

        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            recentPlayedIds = profile.recentTrackIds,
            sessionArtists = emptyList(),
            queueSize = req.queueSize.coerceIn(1, 20),
            noveltyBudget = req.noveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = seeds.first()))
    }
}

private fun List<Track>.filterForRec(profile: RecService.UserProfile, queue: List<Track>): List<Track> {
    val queueIds = queue.map { it.id }.toSet()
    return this
        .filter { t -> t.id !in queueIds }
        .filter { t ->
            val fp = TrackFingerprint.of(t.title)
            fp.isBlank() || fp !in profile.seenFingerprints
        }
        .sortedBy { it.title.length }
        .distinctBy { TrackFingerprint.of(it.title) + "|" + it.artist.normArtist() }
}
