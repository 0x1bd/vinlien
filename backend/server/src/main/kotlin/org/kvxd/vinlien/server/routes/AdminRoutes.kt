package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.services.AdminStatsService
import org.kvxd.vinlien.shared.models.auth.ChangePasswordReq
import org.kvxd.vinlien.shared.models.auth.User
import org.mindrot.jbcrypt.BCrypt

fun Route.adminRoutes() {

    get("/api/admin/stats") {
        if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(AdminStatsService.computeStats())
    }

    post("/api/admin/approve/{id}") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        dbQuery { Users.update({ Users.id eq id }) { it[role] = "APPROVED" } }
        call.respond(HttpStatusCode.OK)
    }

    get("/api/admin/users") {
        if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden)
        val users = dbQuery {
            Users.selectAll().map { User(it[Users.id], it[Users.username], it[Users.role]) }
        }
        call.respond(users)
    }

    delete("/api/admin/users/{id}") {
        if (!call.isAdmin()) return@delete call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val currentUserId = call.getUserId()

        if (id == currentUserId) return@delete call.respond(
            HttpStatusCode.BadRequest, "Cannot delete your own admin account"
        )

        dbQuery {
            History.deleteWhere { History.userId eq id }
            val playlistIds = Playlists.selectAll().where { Playlists.userId eq id }.map { it[Playlists.id] }
            if (playlistIds.isNotEmpty()) PlaylistTracks.deleteWhere { PlaylistTracks.playlistId inList playlistIds }
            Playlists.deleteWhere { Playlists.userId eq id }
            Users.deleteWhere { Users.id eq id }
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/admin/users/{id}/password") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = call.receive<ChangePasswordReq>()
        dbQuery {
            Users.update({ Users.id eq id }) { it[passwordHash] = BCrypt.hashpw(req.newPassword, BCrypt.gensalt()) }
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/admin/cache/clear") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Forbidden)
        CacheManager.clearAll()
        call.respond(HttpStatusCode.OK)
    }
}

private suspend fun ApplicationCall.isAdmin(): Boolean {
    val username = getUsername() ?: return false
    return dbQuery {
        Users.selectAll().where { (Users.username eq username) and (Users.role eq "ADMIN") }.count() > 0
    }
}
