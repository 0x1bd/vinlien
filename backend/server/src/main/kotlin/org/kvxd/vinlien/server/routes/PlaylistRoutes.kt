package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.shared.models.Playlist
import org.kvxd.vinlien.shared.models.Track
import java.util.UUID

@Serializable
data class PlaylistCreateReq(val name: String)

@Serializable
data class PlaylistEditReq(val name: String, val description: String?, val imageUrl: String?)

fun Route.playlistRoutes() {
    route("/api/playlists") {
        get {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString() ?: return@get call.respond(
                HttpStatusCode.Unauthorized
            )
            val response = dbQuery {
                val userPlaylists = Playlists.selectAll().where { Playlists.userId eq userId }.associate {
                    it[Playlists.id] to Playlist(
                        id = it[Playlists.id],
                        userId = userId,
                        name = it[Playlists.name],
                        description = it[Playlists.description],
                        imageUrl = it[Playlists.imageUrl]
                    )
                }.toMutableMap()

                fun ensureSystemPlaylist(name: String) {
                    if (userPlaylists.values.none { it.name == name }) {
                        val newId = UUID.randomUUID().toString()
                        Playlists.insert {
                            it[this.id] = newId
                            it[this.userId] = userId
                            it[this.name] = name
                        }
                        userPlaylists[newId] = Playlist(newId, userId, name)
                    }
                }

                ensureSystemPlaylist("Liked Songs")
                ensureSystemPlaylist("Disliked Songs")

                if (userPlaylists.isNotEmpty()) {
                    (PlaylistTracks innerJoin Tracks).selectAll()
                        .where { PlaylistTracks.playlistId inList userPlaylists.keys }
                        .orderBy(PlaylistTracks.position to SortOrder.ASC)
                        .forEach {
                            val track = it.toTrack()
                            userPlaylists[it[PlaylistTracks.playlistId]]?.tracks?.add(track)
                        }
                }
                userPlaylists.values.toList()
            }
            call.respond(response)
        }

        post {
            val userId =
                call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized
                )
            val req = call.receive<PlaylistCreateReq>()
            val newId = UUID.randomUUID().toString()

            dbQuery {
                Playlists.insert {
                    it[id] = newId
                    it[this.userId] = userId
                    it[name] = req.name
                }
            }
            call.respond(Playlist(newId, userId, req.name))
        }

        put("/{id}/info") {
            val playlistId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<PlaylistEditReq>()

            dbQuery {
                Playlists.update({ Playlists.id eq playlistId }) {
                    it[name] = req.name
                    it[description] = req.description
                    it[imageUrl] = req.imageUrl
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/liked/toggle") {
            val userId =
                call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized
                )
            val track = call.receive<Track>()

            dbQuery {
                DatabaseFactory.insertOrUpdateTrack(track)

                val dislikedPlId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
                    .singleOrNull()?.get(Playlists.id)
                if (dislikedPlId != null) {
                    PlaylistTracks.deleteWhere { (playlistId eq dislikedPlId) and (trackId eq track.id) }
                }

                val likedPlId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Liked Songs") }
                    .singleOrNull()?.get(Playlists.id) ?: return@dbQuery

                val existing = PlaylistTracks.selectAll()
                    .where { (PlaylistTracks.playlistId eq likedPlId) and (PlaylistTracks.trackId eq track.id) }
                    .singleOrNull()

                if (existing != null) {
                    PlaylistTracks.deleteWhere { (playlistId eq likedPlId) and (trackId eq track.id) }
                } else {
                    val maxPos = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId eq likedPlId }
                        .maxByOrNull { it[PlaylistTracks.position] }?.get(PlaylistTracks.position) ?: 0
                    PlaylistTracks.insert {
                        it[playlistId] = likedPlId
                        it[trackId] = track.id
                        it[position] = maxPos + 1
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/disliked/toggle") {
            val userId =
                call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized
                )
            val track = call.receive<Track>()

            dbQuery {
                DatabaseFactory.insertOrUpdateTrack(track)

                val likedPlId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Liked Songs") }
                    .singleOrNull()?.get(Playlists.id)
                if (likedPlId != null) {
                    PlaylistTracks.deleteWhere { (playlistId eq likedPlId) and (trackId eq track.id) }
                }

                val dislikedPlId = Playlists.selectAll()
                    .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
                    .singleOrNull()?.get(Playlists.id) ?: return@dbQuery

                val existing = PlaylistTracks.selectAll()
                    .where { (PlaylistTracks.playlistId eq dislikedPlId) and (PlaylistTracks.trackId eq track.id) }
                    .singleOrNull()

                if (existing != null) {
                    PlaylistTracks.deleteWhere { (playlistId eq dislikedPlId) and (trackId eq track.id) }
                } else {
                    val maxPos = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId eq dislikedPlId }
                        .maxByOrNull { it[PlaylistTracks.position] }?.get(PlaylistTracks.position) ?: 0
                    PlaylistTracks.insert {
                        it[playlistId] = dislikedPlId
                        it[trackId] = track.id
                        it[position] = maxPos + 1
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/{id}/tracks") {
            val playlistId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val track = call.receive<Track>()

            dbQuery {
                DatabaseFactory.insertOrUpdateTrack(track)
                val maxPos = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId eq playlistId }
                    .maxByOrNull { it[PlaylistTracks.position] }?.get(PlaylistTracks.position) ?: 0
                PlaylistTracks.insert {
                    it[this.playlistId] = playlistId
                    it[trackId] = track.id
                    it[position] = maxPos + 1
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        put("/{id}/tracks") {
            val playlistId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val newTracks = call.receive<List<Track>>()

            dbQuery {
                newTracks.forEach { DatabaseFactory.insertOrUpdateTrack(it) }

                PlaylistTracks.deleteWhere { PlaylistTracks.playlistId eq playlistId }

                newTracks.forEachIndexed { idx, track ->
                    PlaylistTracks.insert {
                        it[this.playlistId] = playlistId
                        it[trackId] = track.id
                        it[position] = idx
                    }
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        delete("/{id}") {
            val playlistId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            dbQuery { Playlists.deleteWhere { Playlists.id eq playlistId } }
            call.respond(HttpStatusCode.OK)
        }
    }
}