package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.server.CacheManager

fun Route.catalogRoutes(engine: AggregationEngine) {
    get("/api/artist/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        CacheManager.artistInfo.get(name)?.let { call.respond(it); return@get }
        val info = engine.getArtistInfo(name)
        if (info != null) {
            CacheManager.artistInfo.put(name, info)
            call.respond(info)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/api/artist/{name}/albums") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        CacheManager.artistAlbums.get(name)?.let { call.respond(it); return@get }
        val albums = engine.getArtistAlbums(name).filter { it.artworkUrl != null }
        CacheManager.artistAlbums.put(name, albums)
        call.respond(albums)
    }

    get("/api/artist/{name}/tracks") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        CacheManager.artistTracks.get(name)?.let { call.respond(it); return@get }
        val tracks = engine.getArtistTopTracks(name)
        CacheManager.artistTracks.put(name, tracks)
        call.respond(tracks)
    }

    get("/api/album/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        CacheManager.albumDetail.get(id)?.let { call.respond(it); return@get }
        val album = engine.getAlbum(id)
        if (album != null) {
            CacheManager.albumDetail.put(id, album)
            call.respond(album)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/api/album/{artist}/{title}") {
        val artist = call.parameters["artist"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val title = call.parameters["title"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val cacheKey = "album:$artist:$title"
        CacheManager.albumDetail.get(cacheKey)?.let { call.respond(it); return@get }
        val album = engine.getAlbum(artist, title)
        if (album != null) {
            CacheManager.albumDetail.put(cacheKey, album)
            call.respond(album)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
