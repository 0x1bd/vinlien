package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.shared.models.SearchResponse
import org.kvxd.vinlien.shared.models.Track

private val sseJson = Json { encodeDefaults = true }

internal val searchCache = TtlCache<String, SearchResponse>(ttlMs = 30 * 60 * 1000L, maxSize = 500)

private object TrackDeduplicator {
    fun fingerprint(title: String): String {
        var t = title.lowercase()
        if (t.contains(" - ")) t = t.substringAfter(" - ")
        t = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
        t = t.replace(Regex("[^a-z0-9 ]"), "")
        t = t.replace(Regex("(?i)\\b(official|music video|lyric video|audio|live|remix|hd|hq|ft|feat)\\b"), "")
        return t.trim().replace(Regex("\\s+"), " ")
    }
}

@Serializable
data class RecReq(val queue: List<Track>)

fun Route.searchRoutes(engine: AggregationEngine) {
    get("/api/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val providersParam = call.request.queryParameters["providers"]

        if (query.isBlank()) {
            call.respond(SearchResponse(emptyList(), emptyList()))
            return@get
        }

        val cacheKey = "$query|${providersParam ?: ""}"
        searchCache.get(cacheKey)?.let {
            call.respond(it)
            return@get
        }

        val tracks = engine.searchTracks(query)
            .filter { it.artworkUrl != null }
            .distinctBy { TrackDeduplicator.fingerprint(it.title) + it.artist.lowercase().take(6) }

        val albums = engine.searchAlbums(query)
            .filter { it.artworkUrl != null }
            .distinctBy { TrackDeduplicator.fingerprint(it.title) + TrackDeduplicator.fingerprint(it.artist) }

        val response = SearchResponse(tracks, albums)
        searchCache.put(cacheKey, response)
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

                val dislikedPlaylistId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
                    .singleOrNull()?.get(Playlists.id)

                if (dislikedPlaylistId != null) {
                    dislikedTitles = (PlaylistTracks innerJoin Tracks)
                        .selectAll()
                        .where { PlaylistTracks.playlistId eq dislikedPlaylistId }
                        .map { it[Tracks.title] }
                }
            }
        }

        val seenTitleFingerprints = (queue.map { it.title } + recentHistoryTitles + dislikedTitles)
            .map { TrackDeduplicator.fingerprint(it) }

        fun isAlreadySeen(candidateTitle: String): Boolean {
            val fingerprint = TrackDeduplicator.fingerprint(candidateTitle)
            if (fingerprint.isBlank()) return false
            return seenTitleFingerprints.any { it == fingerprint }
        }

        val recs = engine.getRecommendations(currentTrack)
        val freshRec = recs.firstOrNull { r ->
            queue.none { it.id == r.id } && !isAlreadySeen(r.title)
        }

        call.respond(if (freshRec != null) listOf(freshRec) else emptyList())
    }

    get("/api/artist/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val info = engine.getArtistInfo(name)
        if (info != null) call.respond(info) else call.respond(HttpStatusCode.NotFound)
    }

    get("/api/artist/{name}/albums") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val albums = engine.getArtistAlbums(name)
            .distinctBy { it.title.lowercase().replace(Regex("[^a-z]"), "") }
        call.respond(albums)
    }

    get("/api/artist/{name}/tracks") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val providersParam = call.request.queryParameters["providers"]

        val cacheKey = "artist_tracks:$name|${providersParam ?: ""}"
        searchCache.get(cacheKey)?.let {
            call.respond(it.tracks)
            return@get
        }

        val targetArtistNormalized = name.lowercase().replace(Regex("[^a-z0-9]"), "")

        val tracks = engine.searchTracks(name)
            .filter { it.artworkUrl != null }
            .filter { track ->
                track.artists.any { it.lowercase().replace(Regex("[^a-z0-9]"), "") == targetArtistNormalized } ||
                        track.artist.lowercase().replace(Regex("[^a-z0-9]"), "") == targetArtistNormalized
            }
            .distinctBy { TrackDeduplicator.fingerprint(it.title) }

        searchCache.put(cacheKey, SearchResponse(tracks, emptyList()))
        call.respond(tracks)
    }

    get("/api/album/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val album = engine.getAlbum(id)
        if (album != null) call.respond(album) else call.respond(HttpStatusCode.NotFound)
    }

    sse("/api/search/stream") {
        val query = call.request.queryParameters["q"] ?: ""
        if (query.isBlank()) {
            send(ServerSentEvent(event = "done", data = ""))
            return@sse
        }

        val cacheKey = "$query|"
        val cached = searchCache.get(cacheKey)
        if (cached != null) {
            send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), cached)))
            send(ServerSentEvent(event = "done", data = ""))
            return@sse
        }

        var lastResponse: SearchResponse? = null
        engine.searchStreaming(query).collect { raw ->
            val tracks = raw.tracks
                .distinctBy { TrackDeduplicator.fingerprint(it.title) + it.artist.lowercase().take(6) }
            val albums = raw.albums
                .distinctBy { TrackDeduplicator.fingerprint(it.title) + TrackDeduplicator.fingerprint(it.artist) }
            val response = SearchResponse(tracks, albums)
            if (tracks.isNotEmpty() || albums.isNotEmpty()) {
                lastResponse = response
                send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), response)))
            }
        }

        lastResponse?.let { searchCache.put(cacheKey, it) }
        send(ServerSentEvent(event = "done", data = ""))
    }
}
