package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.DatabaseFactory
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.History
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.services.HomeFeedService
import org.kvxd.vinlien.server.services.RecService
import org.kvxd.vinlien.shared.models.media.Track

private const val TRENDING_CACHE_KEY = "trending"

fun Route.feedRoutes(engine: AggregationEngine) {
    post("/api/history") {
        val track = call.receive<Track>()
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        dbQuery {
            DatabaseFactory.insertOrUpdateTrack(track)
            History.insert {
                it[this.userId] = userId
                it[trackId] = track.id
                it[timestamp] = System.currentTimeMillis()
            }
        }
        CacheManager.homeFeed.remove(userId)
        RecService.invalidate(userId)
        call.respond(HttpStatusCode.OK)
    }

    get("/api/home/feed") {
        val userId = call.getUserId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        CacheManager.homeFeed.get(userId)?.let { call.respond(it); return@get }
        val feed = HomeFeedService.buildFeed(userId)
        CacheManager.homeFeed.put(userId, feed)
        call.respond(feed)
    }

    get("/api/home/trending") {
        CacheManager.trending.get(TRENDING_CACHE_KEY)?.let {
            call.respond(it)
            return@get
        }
        val trending = engine.getTrending()
        if (trending.isNotEmpty()) {
            CacheManager.trending.put(TRENDING_CACHE_KEY, trending)
            call.respond(trending)
        } else {
            call.respond(emptyList<Track>())
        }
    }
}
