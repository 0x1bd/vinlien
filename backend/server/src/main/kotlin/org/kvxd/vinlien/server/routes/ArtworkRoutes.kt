package org.kvxd.vinlien.server.routes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.kvxd.vinlien.server.CacheManager

private val artworkClient = HttpClient(CIO) {
    engine { requestTimeout = 8_000 }
    followRedirects = true
}

fun Route.artworkRoutes() {
    get("/api/artwork") {
        val url = call.request.queryParameters["url"]?.trim()
            ?: return@get call.respond(HttpStatusCode.BadRequest)

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@get call.respond(HttpStatusCode.BadRequest)
        }

        CacheManager.artwork.get(url)?.let { (bytes, ct) ->
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.parse(ct))
            return@get
        }

        try {
            val upstream = artworkClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 Vinlien")
            }
            if (!upstream.status.isSuccess()) {
                call.respond(upstream.status)
                return@get
            }
            val bytes = upstream.readRawBytes()
            val ct = upstream.contentType()?.toString() ?: "image/jpeg"
            CacheManager.artwork.put(url, bytes to ct)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.parse(ct))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway)
        }
    }
}
