package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.services.PlaylistService
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class PlaylistCreateReq(val name: String)

@Serializable
data class PlaylistEditReq(val name: String, val description: String?, val imageUrl: String?)

fun Route.playlistRoutes() {
    route("/api/playlists") {
        get {
            val userId = call.getUserId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(PlaylistService.getForUser(userId))
        }

        post {
            val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<PlaylistCreateReq>()
            call.respond(PlaylistService.create(userId, req.name))
        }

        put("/{id}/info") {
            val playlistId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<PlaylistEditReq>()
            PlaylistService.updateInfo(playlistId, req.name, req.description, req.imageUrl)
            call.respond(HttpStatusCode.OK)
        }

        post("/liked/toggle") {
            val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val track = call.receive<Track>()
            PlaylistService.toggleTrack(userId, track, targetName = "Liked Songs", oppositeName = "Disliked Songs")
            call.respond(HttpStatusCode.OK)
        }

        post("/disliked/toggle") {
            val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val track = call.receive<Track>()
            PlaylistService.toggleTrack(userId, track, targetName = "Disliked Songs", oppositeName = "Liked Songs")
            call.respond(HttpStatusCode.OK)
        }

        post("/{id}/tracks") {
            val playlistId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val track = call.receive<Track>()
            PlaylistService.addTrack(playlistId, track)
            call.respond(HttpStatusCode.OK)
        }

        put("/{id}/tracks") {
            val playlistId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newTracks = call.receive<List<Track>>()
            PlaylistService.replaceTracks(playlistId, newTracks)
            call.respond(HttpStatusCode.OK)
        }

        delete("/{id}") {
            val playlistId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            PlaylistService.delete(playlistId)
            call.respond(HttpStatusCode.OK)
        }
    }
}
