package org.kvxd.vinlien.server.routes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.shared.Track
import org.slf4j.LoggerFactory
import java.io.File

private val proxyClient = HttpClient(CIO) {
    engine { requestTimeout = 0 }
    followRedirects = true
}

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

        if (urlOrPath.startsWith("http")) {
            val rangeHeader = call.request.headers[HttpHeaders.Range]
            val upstream = proxyClient.get(urlOrPath) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 Vinlien")
                if (rangeHeader != null) header(HttpHeaders.Range, rangeHeader)
            }
            val ct = upstream.contentType()?.toString() ?: "audio/mpeg"
            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
            upstream.headers[HttpHeaders.ContentRange]?.let {
                call.response.headers.append(HttpHeaders.ContentRange, it)
            }
            upstream.headers[HttpHeaders.ContentLength]?.let {
                call.response.headers.append(HttpHeaders.ContentLength, it)
            }
            call.respondBytesWriter(contentType = ContentType.parse(ct), status = upstream.status) {
                upstream.bodyAsChannel().copyTo(this)
            }
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