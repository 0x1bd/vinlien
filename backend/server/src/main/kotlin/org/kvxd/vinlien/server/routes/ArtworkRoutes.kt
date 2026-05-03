package org.kvxd.vinlien.server.routes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.server.db.repositories.TrackRepository
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager
import org.slf4j.LoggerFactory

@Serializable
data class ArtworkEnrichRequest(val id: String, val title: String, val artist: String)

@Serializable
data class ArtworkEnrichResponse(val artworkUrl: String?)

@Serializable
data class ArtworkBatchEnrichRequest(val tracks: List<ArtworkEnrichRequest>)

@Serializable
data class ArtworkBatchEnrichResponse(val results: Map<String, String?>)

private val artworkClient = HttpClient(CIO) {
    engine { requestTimeout = 8_000 }
    followRedirects = true
}

private val logger = LoggerFactory.getLogger("ArtworkProxy")

private val inflightMutex = Mutex()
private val inflightMap = mutableMapOf<String, CompletableDeferred<Pair<ByteArray, String>?>>()

fun Route.artworkRoutes(engine: AggregationEngine) {
    get("/api/artwork") {
        val url = call.request.queryParameters["url"]?.trim()
            ?: return@get call.respond(HttpStatusCode.BadRequest)

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@get call.respond(HttpStatusCode.BadRequest)
        }

        CacheManager.artwork.get(url)?.let { (bytes, ct) ->
            call.respondCached(bytes, ct)
            return@get
        }

        CacheManager.diskArtwork.get(url)?.let { (bytes, ct) ->
            CacheManager.artwork.put(url, bytes to ct)
            call.respondCached(bytes, ct)
            return@get
        }

        val (deferred, isOwner) = inflightMutex.withLock {
            val existing = inflightMap[url]
            if (existing != null) {
                existing to false
            } else {
                val d = CompletableDeferred<Pair<ByteArray, String>?>()
                inflightMap[url] = d
                d to true
            }
        }

        if (!isOwner) {
            val result = deferred.await()
                ?: return@get call.respond(HttpStatusCode.BadGateway)
            call.respondCached(result.first, result.second)
            return@get
        }

        try {
            val upstream = artworkClient.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 Vinlien")
            }
            if (!upstream.status.isSuccess()) {
                logger.warn("Upstream {} for: {}", upstream.status.value, url)
                deferred.complete(null)
                call.respond(upstream.status)
                return@get
            }
            val bytes = upstream.readRawBytes()
            val ct = upstream.contentType()?.toString() ?: "image/jpeg"
            CacheManager.artwork.put(url, bytes to ct)
            CacheManager.diskArtwork.put(url, bytes, ct)
            deferred.complete(bytes to ct)
            call.respondCached(bytes, ct)
        } catch (e: Exception) {
            logger.warn("Artwork proxy failed for {}: {}", url, e.message)
            deferred.complete(null)
            call.respond(HttpStatusCode.BadGateway)
        } finally {
            inflightMutex.withLock { inflightMap.remove(url) }
        }
    }

    post("/api/artwork/enrich") {
        val req = call.receive<ArtworkEnrichRequest>()
        call.respond(ArtworkEnrichResponse(enrichTrack(req, engine)))
    }

    post("/api/artwork/enrich/batch") {
        val req = call.receive<ArtworkBatchEnrichRequest>()
        val tracks = req.tracks.take(25)

        val semaphore = Semaphore(5)
        val results: Map<String, String?> = coroutineScope {
            tracks.map { item ->
                async { item.id to semaphore.withPermit { enrichTrack(item, engine) } }
            }.awaitAll()
        }.toMap()
        call.respond(ArtworkBatchEnrichResponse(results))
    }
}

private suspend fun ApplicationCall.respondCached(bytes: ByteArray, ct: String) {
    response.header(HttpHeaders.CacheControl, "public, max-age=2592000, immutable")
    respondBytes(bytes, ContentType.parse(ct))
}

private suspend fun enrichTrack(req: ArtworkEnrichRequest, engine: AggregationEngine): String? {
    val stored = TrackRepository.getArtworkUrl(req.id)
    if (stored != null && !stored.contains("ytimg.com")) return stored

    val artworkUrl = runCatching { engine.enrichArtwork(req.title, req.artist) }.getOrNull()

    if (artworkUrl != null) {
        TrackRepository.updateArtworkUrl(req.id, artworkUrl)
    }

    return artworkUrl
}
