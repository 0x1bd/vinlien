package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.kvxd.vinlien.backends.BackendManager
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.shared.SearchResponse
import org.kvxd.vinlien.shared.Track
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RecReq(val queue: List<Track>)

object SearchCache {
    private val cache = ConcurrentHashMap<String, Pair<Long, SearchResponse>>()
    private const val TTL = 30 * 60 * 1000L
    private const val MAX_SIZE = 500

    fun get(query: String, providers: String?): SearchResponse? {
        val key = "$query|${providers ?: ""}"
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.first > TTL) {
            cache.remove(key)
            return null
        }
        return entry.second
    }

    fun put(query: String, providers: String?, results: SearchResponse) {
        if (cache.size >= MAX_SIZE) cache.clear()
        val key = "$query|${providers ?: ""}"
        cache[key] = System.currentTimeMillis() to results
    }

    fun clear() = cache.clear()
}

fun normalizeForDedup(title: String): String {
    var t = title.lowercase()
    if (t.contains(" - ")) t = t.substringAfter(" - ")
    t = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
    t = t.replace(Regex("[^a-z0-9 ]"), "")
    t = t.replace(Regex("(?i)\\b(official|music video|lyric video|audio|live|remix|hd|hq|ft|feat)\\b"), "")
    return t.trim().replace(Regex("\\s+"), " ")
}

fun Route.searchRoutes(backends: BackendManager) {
    get("/api/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val providersParamRaw = call.request.queryParameters["providers"]
        val preferred = providersParamRaw?.lowercase()?.split(",")?.firstOrNull { it.isNotEmpty() }

        if (query.isBlank()) {
            call.respond(SearchResponse(emptyList(), emptyList()))
            return@get
        }

        val cached = SearchCache.get(query, providersParamRaw)
        if (cached != null) {
            call.respond(cached)
            return@get
        }

        val tracks = backends.search(query, preferred)
            .distinctBy { normalizeForDedup(it.title) + normalizeForDedup(it.artist) }

        val albums = backends.searchAlbums(query, preferred)
            .distinctBy { normalizeForDedup(it.title) + normalizeForDedup(it.artist) }

        val response = SearchResponse(tracks, albums)
        SearchCache.put(query, providersParamRaw, response)
        call.respond(response)
    }

    post("/api/recommendations") {
        val req = call.receive<RecReq>()
        val queue = req.queue
        val currentTrack = queue.lastOrNull() ?: return@post call.respond(emptyList<Track>())

        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()

        var recentHistoryTitles = emptyList<String>()
        var dislikedTitles = emptyList<String>()

        if (userId != null) {
            dbQuery {
                recentHistoryTitles = (History innerJoin Tracks)
                    .selectAll()
                    .where { History.userId eq userId }
                    .orderBy(History.timestamp to SortOrder.DESC)
                    .limit(30)
                    .map { it[Tracks.title] }

                val dislikedPlId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
                    .singleOrNull()?.get(Playlists.id)

                if (dislikedPlId != null) {
                    dislikedTitles = (PlaylistTracks innerJoin Tracks)
                        .selectAll()
                        .where { PlaylistTracks.playlistId eq dislikedPlId }
                        .map { it[Tracks.title] }
                }
            }
        }

        val historyTitlesClean =
            (queue.map { it.title } + recentHistoryTitles + dislikedTitles).map { normalizeForDedup(it) }

        fun isDuplicate(candidateTitle: String): Boolean {
            val cleanCandidate = normalizeForDedup(candidateTitle)
            if (cleanCandidate.isBlank()) return false
            return historyTitlesClean.any {
                it == cleanCandidate || (cleanCandidate.length > 4 && (it.contains(cleanCandidate) || cleanCandidate.contains(
                    it
                )))
            }
        }

        val recs = backends.getRecommendations(currentTrack)
        val validRec = recs.firstOrNull { r ->
            queue.none { it.id == r.id } && !isDuplicate(r.title)
        }

        call.respond(if (validRec != null) listOf(validRec) else emptyList())
    }

    get("/api/artist/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val info = backends.getArtistInfo(name)
        if (info != null) call.respond(info) else call.respond(HttpStatusCode.NotFound)
    }

    get("/api/artist/{name}/albums") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(backends.getArtistAlbums(name))
    }

    get("/api/album/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val album = backends.getAlbum(id)
        if (album != null) call.respond(album) else call.respond(HttpStatusCode.NotFound)
    }
}