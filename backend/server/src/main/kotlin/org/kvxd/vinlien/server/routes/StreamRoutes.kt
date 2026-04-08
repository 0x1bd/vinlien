package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.kvxd.vinlien.backends.BackendManager
import org.kvxd.vinlien.shared.Track
import org.slf4j.LoggerFactory
import java.io.File

fun Route.streamRoutes(backends: BackendManager) {
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

        val urlOrPath = runCatching { backends.getStreamUrl(track, preferred) }.getOrNull()

        if (urlOrPath == null) {
            logger.error("All providers failed to stream track '{} - {}' (ID: {})", artist, title, id)
            return@get call.respond(HttpStatusCode.NotFound)
        }

        if (urlOrPath.startsWith("http")) {
            call.respondRedirect(urlOrPath)
        } else {
            val file = File(urlOrPath)
            if (file.exists()) call.respondFile(file)
            else {
                logger.error("Provider returned local path but file missing: $urlOrPath")
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}