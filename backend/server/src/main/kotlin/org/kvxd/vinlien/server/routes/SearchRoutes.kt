package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.json.Json
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.shared.models.media.SearchResponse

private val sseJson = Json { encodeDefaults = true }

fun Route.searchRoutes(engine: AggregationEngine) {
    get("/api/search") {
        val query = call.request.queryParameters["q"] ?: ""
        val providersParam = call.request.queryParameters["providers"]

        if (query.isBlank()) {
            call.respond(SearchResponse(emptyList(), emptyList()))
            return@get
        }

        val cacheKey = "$query|${providersParam ?: ""}"
        CacheManager.search.get(cacheKey)?.let {
            call.respond(it)
            return@get
        }

        val tracks = engine.searchTracks(query)
            .filter { it.artworkUrl != null }
            .distinctBy { TrackFingerprint.of(it.title) + it.artist.lowercase().take(6) }

        val albums = engine.searchAlbums(query)
            .filter { it.artworkUrl != null }
            .distinctBy { TrackFingerprint.of(it.title) + TrackFingerprint.of(it.artist) }

        val response = SearchResponse(tracks, albums)
        CacheManager.search.put(cacheKey, response)
        call.respond(response)
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
        CacheManager.search.get(cacheKey)?.let {
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
            .distinctBy { TrackFingerprint.of(it.title) }

        CacheManager.search.put(cacheKey, SearchResponse(tracks, emptyList()))
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
        val cached = CacheManager.search.get(cacheKey)
        if (cached != null) {
            send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), cached)))
            send(ServerSentEvent(event = "done", data = ""))
            return@sse
        }

        var lastResponse: SearchResponse? = null
        engine.searchStreaming(query).collect { raw ->
            val tracks = raw.tracks
                .distinctBy { TrackFingerprint.of(it.title) + it.artist.lowercase().take(6) }
            val albums = raw.albums
                .distinctBy { TrackFingerprint.of(it.title) + TrackFingerprint.of(it.artist) }
            val response = SearchResponse(tracks, albums)
            if (tracks.isNotEmpty() || albums.isNotEmpty()) {
                lastResponse = response
                send(ServerSentEvent(data = sseJson.encodeToString(SearchResponse.serializer(), response)))
            }
        }

        lastResponse?.let { CacheManager.search.put(cacheKey, it) }
        send(ServerSentEvent(event = "done", data = ""))
    }
}
