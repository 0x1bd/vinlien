package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.shared.models.*

private val recEngine = RecommendationEngine()

fun Route.recRoutes(engine: AggregationEngine) {

    post("/api/rec") {
        val req = call.receive<RecRequest>()
        val currentTrack = req.queue.lastOrNull()
            ?: return@post call.respond(HttpStatusCode.NoContent)

        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val (history, skips, recentIds, seenFingerprints) = dbQuery {
            val historyRows = (History innerJoin Tracks)
                .selectAll()
                .where { History.userId eq userId }
                .orderBy(History.timestamp to SortOrder.DESC)
                .limit(500)
                .map { row ->
                    RecommendationEngine.HistoryEntry(
                        trackId = row[Tracks.id],
                        artist = row[Tracks.artist],
                        timestampMs = row[History.timestamp]
                    )
                }

            val skipRows = SkipEvents
                .selectAll()
                .where { SkipEvents.userId eq userId }
                .map { row ->
                    RecommendationEngine.SkipEntry(
                        trackId = row[SkipEvents.trackId],
                        artist = row[SkipEvents.artist],
                        playedMs = row[SkipEvents.playedMs]
                    )
                }

            val recentTrackIds = historyRows.take(30).map { it.trackId }

            val dislikedPlaylistId = Playlists.selectAll()
                .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
                .singleOrNull()?.get(Playlists.id)

            val dislikedTitles = if (dislikedPlaylistId != null) {
                (PlaylistTracks innerJoin Tracks)
                    .selectAll()
                    .where { PlaylistTracks.playlistId eq dislikedPlaylistId }
                    .map { it[Tracks.title] }
            } else emptyList()

            val seenTitles = (req.queue.map { it.title } + recentTrackIds
                .let { ids ->
                    Tracks.selectAll().where { Tracks.id inList ids.take(30) }.map { it[Tracks.title] }
                } + dislikedTitles).map { fingerprint(it) }

            Quadruple(historyRows, skipRows, recentTrackIds, seenTitles)
        }

        val vector = recEngine.buildListeningVector(history, skips, Config.data.recDecayDays)

        val candidates = engine.getRecommendations(currentTrack)
            .filter { t -> req.queue.none { it.id == t.id } }
            .filter { t -> fingerprint(t.title).let { fp -> fp.isBlank() || fp !in seenFingerprints } }

        val result = recEngine.pickWithDiversity(
            candidates = candidates,
            vector = vector,
            seedTrack = currentTrack,
            recentPlayedIds = recentIds,
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
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        dbQuery {
            SkipEvents.insert {
                it[this.userId] = userId
                it[trackId] = req.trackId
                it[artist] = req.artist
                it[playedMs] = req.playedMs
                it[timestamp] = System.currentTimeMillis()
            }
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/radio") {
        val req = call.receive<RadioRequest>()
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val (history, skips, recentIds) = dbQuery {
            val historyRows = (History innerJoin Tracks)
                .selectAll()
                .where { History.userId eq userId }
                .orderBy(History.timestamp to SortOrder.DESC)
                .limit(500)
                .map { row ->
                    RecommendationEngine.HistoryEntry(
                        trackId = row[Tracks.id],
                        artist = row[Tracks.artist],
                        timestampMs = row[History.timestamp]
                    )
                }
            val skipRows = SkipEvents
                .selectAll()
                .where { SkipEvents.userId eq userId }
                .map { row ->
                    RecommendationEngine.SkipEntry(
                        trackId = row[SkipEvents.trackId],
                        artist = row[SkipEvents.artist],
                        playedMs = row[SkipEvents.playedMs]
                    )
                }
            Triple(historyRows, skipRows, historyRows.take(30).map { it.trackId })
        }

        val vector = recEngine.buildListeningVector(history, skips, Config.data.recDecayDays)

        val initialCandidates = engine.getRecommendations(req.seedTrack)
        val effectiveSeed = if (recEngine.shouldReseed(req.tracksPlayedInSession, Config.data.radioReseedInterval)) {
            recEngine.pickReseedTrack(initialCandidates, req.seedTrack, vector) ?: req.seedTrack
        } else {
            req.seedTrack
        }

        val candidates = if (effectiveSeed.id != req.seedTrack.id) {
            engine.getRecommendations(effectiveSeed)
        } else {
            initialCandidates
        }.filter { t -> req.queue.none { it.id == t.id } }

        val queueSize = req.queueSize.coerceIn(1, 50)
        val tracks = recEngine.buildRadioQueue(
            candidates = candidates,
            vector = vector,
            seedTrack = effectiveSeed,
            recentPlayedIds = recentIds,
            sessionArtists = req.sessionArtists,
            queueSize = queueSize,
            decayDays = Config.data.recDecayDays,
            noveltyBudget = Config.data.recNoveltyBudget,
            maxConsecutiveSameArtist = Config.data.recMaxConsecutiveSameArtist
        )

        call.respond(RadioResponse(tracks = tracks, seedTrack = effectiveSeed))
    }
}

private fun fingerprint(title: String): String {
    var t = title.lowercase()
    if (t.contains(" - ")) t = t.substringAfter(" - ")
    t = t.replace(Regex("\\(.*?\\)|\\[.*?]"), "")
    t = t.replace(Regex("[^a-z0-9 ]"), "")
    t = t.replace(Regex("\\b(official|music video|lyric video|audio|live|remix|hd|hq|ft|feat)\\b"), "")
    return t.trim().replace(Regex("\\s+"), " ")
}

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)