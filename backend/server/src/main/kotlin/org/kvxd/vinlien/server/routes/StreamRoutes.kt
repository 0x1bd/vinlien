package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.shared.models.media.Track
import org.slf4j.LoggerFactory

@Serializable
data class StreamResponse(val streamUrl: String, val provider: String)

fun Route.streamRoutes(engine: AggregationEngine) {
    val logger = LoggerFactory.getLogger("StreamRoutes")

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
            return@get call.respond(HttpStatusCode.BadRequest)
        }

        val track = Track(id = id, artist = artist, title = title, durationMs = durationMs, streamUrl = streamUrl)
        val urlOrPath = runCatching { engine.resolveStream(track, preferred) }.getOrNull()

        if (urlOrPath == null) {
            logger.error("All providers failed to stream track '{} - {}' (ID: {})", artist, title, id)
            return@get call.respond(HttpStatusCode.NotFound)
        }

        val provider = when {
            id.startsWith("sc:") -> "SoundCloud"
            id.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> "Invidious"
            else -> "Invidious"
        }

        call.respond(StreamResponse(streamUrl = urlOrPath, provider = provider))
    }
}
