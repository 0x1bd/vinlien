package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.TtlCache
import org.kvxd.vinlien.shared.models.media.Track
import org.slf4j.LoggerFactory

@Serializable
data class StreamResponse(val streamUrl: String, val provider: String)

@Serializable
data class StreamErrorResponse(val error: String, val details: String? = null)

fun Route.streamRoutes(engine: AggregationEngine) {
    val logger = LoggerFactory.getLogger("StreamRoutes")
    val streamCache = TtlCache<String, StreamResponse>(ttlMs = 10 * 60 * 1000L, maxSize = 200)

    get("/api/stream") {
        val id = call.request.queryParameters["id"] ?: ""
        val artist = call.request.queryParameters["artist"] ?: ""
        val title = call.request.queryParameters["title"] ?: ""
        val streamUrl = call.request.queryParameters["streamUrl"]
        val durationMs = call.request.queryParameters["durationMs"]?.toLongOrNull() ?: 0L
        val preferred = call.request.queryParameters["providers"]?.lowercase()
            ?.split(",")?.firstOrNull { it.isNotEmpty() }

        if (id.isBlank() || title.isBlank()) {
            logger.warn("Rejecting stream request: Missing ID or Title (id='$id', title='$title')")
            return@get call.respond(HttpStatusCode.BadRequest, StreamErrorResponse(error = "Missing track ID or title"))
        }

        val cacheKey = id
        val cached = streamCache.get(cacheKey)
        if (cached != null) {
            return@get call.respond(cached)
        }

        val track = Track(id = id, artist = artist, title = title, durationMs = durationMs, streamUrl = streamUrl)
        val resolveResult = runCatching { engine.resolveStream(track, preferred) }

        if (resolveResult.isFailure) {
            val exception = resolveResult.exceptionOrNull()
            val errorMsg = exception?.message ?: "Unknown error"
            logger.error("Stream resolution failed for '{} - {}' (ID: {}): {}", artist, title, id, errorMsg)
            return@get call.respond(HttpStatusCode.NotFound, StreamErrorResponse(
                error = "No stream available",
                details = errorMsg
            ))
        }

        val urlOrPath = resolveResult.getOrNull()
        if (urlOrPath == null) {
            logger.error("Stream resolution returned null for '{} - {}' (ID: {})", artist, title, id)
            return@get call.respond(HttpStatusCode.NotFound, StreamErrorResponse(error = "No stream available"))
        }

        val provider = when {
            id.startsWith("sc:") -> "SoundCloud"
            id.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> "Invidious"
            else -> "Invidious"
        }

        val response = StreamResponse(streamUrl = urlOrPath, provider = provider)
        streamCache.put(cacheKey, response)
        call.respond(response)
    }
}
