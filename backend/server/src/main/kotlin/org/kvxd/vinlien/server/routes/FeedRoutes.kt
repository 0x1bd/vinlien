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
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.shared.HomeFeed
import org.kvxd.vinlien.shared.Track

internal val trendingCache = TtlCache<String, List<Track>>(ttlMs = 30 * 60 * 1000L)
private const val TRENDING_CACHE_KEY = "trending"

fun Route.feedRoutes(engine: AggregationEngine) {
    put("/api/tracks") {
        val track = call.receive<Track>()
        dbQuery { DatabaseFactory.insertOrUpdateTrack(track) }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/history") {
        val track = call.receive<Track>()
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        dbQuery {
            DatabaseFactory.insertOrUpdateTrack(track)
            History.insert {
                it[this.userId] = userId
                it[trackId] = track.id
                it[timestamp] = System.currentTimeMillis()
            }
        }
        call.respond(HttpStatusCode.OK)
    }

    get("/api/home/feed") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()
            ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val parsedTracks = dbQuery {
            (History innerJoin Tracks)
                .selectAll()
                .where { History.userId eq userId }
                .orderBy(History.timestamp to SortOrder.DESC)
                .limit(200)
                .map { it.toTrack() }
        }

        val recentlyPlayed = parsedTracks.distinctBy { it.canonicalId ?: it.id }.take(10)
        val trackCounts = parsedTracks.groupingBy { it }.eachCount()
        val listenAgain = trackCounts.filter { it.value >= 2 }.keys.toList().shuffled().take(10)
        val recentIds = recentlyPlayed.take(15).map { it.id }
        val forgottenFavorites =
            trackCounts.filter { it.value >= 2 && !recentIds.contains(it.key.id) }.keys.toList().take(10)
        val artists = parsedTracks.flatMap { it.artists }.distinct().shuffled().take(3)

        call.respond(HomeFeed(recentlyPlayed, listenAgain, forgottenFavorites, artists))
    }

    get("/api/home/trending") {
        trendingCache.get(TRENDING_CACHE_KEY)?.let {
            call.respond(it)
            return@get
        }

        val trending = engine.getTrending()
        if (trending.isNotEmpty()) {
            trendingCache.put(TRENDING_CACHE_KEY, trending)
            call.respond(trending)
        } else {
            call.respond(emptyList<Track>())
        }
    }
}
