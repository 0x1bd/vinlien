package org.kvxd.vinlien.server.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.shared.models.media.SearchResponse
import org.kvxd.vinlien.shared.models.media.Track

private val sseJson = Json { encodeDefaults = true }

private fun List<Track>.deduplicateTracks() =
    distinctBy { TrackFingerprint.of(it.title) + it.artist.lowercase().take(6) }

fun Route.searchRoutes(engine: AggregationEngine) {
    get("/api/search") {
        val query = call.request.queryParameters["q"] ?: ""

        if (query.isBlank()) {
            call.respond(SearchResponse(emptyList(), emptyList()))
            return@get
        }

        CacheManager.search.get(query)?.let {
            call.respond(it)
            return@get
        }

        val (tracks, albums) = coroutineScope {
            val tracksDeferred = async { engine.searchTracks(query).filter { it.artworkUrl != null } }
            val albumsDeferred = async {
                engine.searchAlbums(query)
                    .filter { it.artworkUrl != null }
                    .distinctBy { TrackFingerprint.of(it.title) + TrackFingerprint.of(it.artist) }
            }
            tracksDeferred.await() to albumsDeferred.await()
        }

        val response = SearchResponse(tracks.deduplicateTracks(), albums)
        CacheManager.search.put(query, response)
        call.respond(response)
    }

    sse("/api/search/stream") {
        val query = call.request.queryParameters["q"] ?: ""
        if (query.isBlank()) {
            send(ServerSentEvent(event = "done", data = ""))
            return@sse
        }

        val cached = CacheManager.search.get(query)
        if (cached != null) {
            send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), cached)))
            send(ServerSentEvent(event = "done", data = ""))
            return@sse
        }

        var lastResponse: SearchResponse? = null
        engine.searchStreaming(query).collect { raw ->
            val tracks = raw.tracks.deduplicateTracks()
            val albums = raw.albums
                .distinctBy { TrackFingerprint.of(it.title) + TrackFingerprint.of(it.artist) }
            val response = SearchResponse(tracks, albums)
            if (tracks.isNotEmpty() || albums.isNotEmpty()) {
                lastResponse = response
                send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), response)))
            }
        }

        lastResponse?.let { CacheManager.search.put(query, it) }
        send(ServerSentEvent(event = "done", data = ""))
    }
}
