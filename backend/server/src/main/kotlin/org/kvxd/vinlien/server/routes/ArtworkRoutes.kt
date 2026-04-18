package org.kvxd.vinlien.server.routes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.Tracks
import org.slf4j.LoggerFactory

@Serializable
data class ArtworkEnrichRequest(val id: String, val title: String, val artist: String)

@Serializable
data class ArtworkEnrichResponse(val artworkUrl: String?)

private val artworkClient = HttpClient(CIO) {
    engine { requestTimeout = 8_000 }
    followRedirects = true
}

private val artworkLogger = LoggerFactory.getLogger("ArtworkProxy")

fun Route.artworkRoutes(engine: AggregationEngine) {
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

        CacheManager.diskArtwork.get(url)?.let { (bytes, ct) ->
            CacheManager.artwork.put(url, bytes to ct)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.parse(ct))
            return@get
        }

        try {
            val upstream = artworkClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 Vinlien")
            }
            if (!upstream.status.isSuccess()) {
                artworkLogger.warn("Upstream returned ${upstream.status.value} for: $url")
                call.respond(upstream.status)
                return@get
            }
            val bytes = upstream.readRawBytes()
            val ct = upstream.contentType()?.toString() ?: "image/jpeg"
            CacheManager.artwork.put(url, bytes to ct)
            CacheManager.diskArtwork.put(url, bytes, ct)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.parse(ct))
        } catch (e: Exception) {
            artworkLogger.warn("Artwork proxy failed for {}: {}", url, e.message)
            call.respond(HttpStatusCode.BadGateway)
        }
    }

    post("/api/artwork/enrich") {
        val req = call.receive<ArtworkEnrichRequest>()

        val storedArtwork = dbQuery {
            Tracks.selectAll().where { Tracks.id eq req.id }
                .map { it[Tracks.artworkUrl] }
                .firstOrNull()
        }
        if (storedArtwork != null && !storedArtwork.contains("ytimg.com")) {
            call.respond(ArtworkEnrichResponse(storedArtwork))
            return@post
        }

        val artworkUrl = runCatching { engine.enrichArtwork(req.title, req.artist) }.getOrNull()

        if (artworkUrl != null) {
            dbQuery {
                Tracks.update({ Tracks.id eq req.id }) {
                    it[Tracks.artworkUrl] = artworkUrl
                }
            }
        }

        call.respond(ArtworkEnrichResponse(artworkUrl))
    }
}
