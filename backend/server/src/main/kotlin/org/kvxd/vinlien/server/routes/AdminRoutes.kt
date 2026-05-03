package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.db.repositories.HistoryRepository
import org.kvxd.vinlien.server.db.repositories.PlaylistRepository
import org.kvxd.vinlien.server.db.repositories.UserRepository
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.getUsername
import org.kvxd.vinlien.server.services.AdminStatsService
import org.kvxd.vinlien.shared.models.auth.ChangePasswordReq
import org.mindrot.jbcrypt.BCrypt

fun Route.adminRoutes() {

    get("/api/admin/stats") {
        if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(AdminStatsService.computeStats())
    }

    post("/api/admin/approve/{id}") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        UserRepository.approveUser(id)
        call.respond(HttpStatusCode.OK)
    }

    get("/api/admin/users") {
        if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(UserRepository.getAllUsers())
    }

    delete("/api/admin/users/{id}") {
        if (!call.isAdmin()) return@delete call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val currentUserId = call.getUserId()

        if (id == currentUserId) return@delete call.respond(
            HttpStatusCode.BadRequest, "Cannot delete your own admin account"
        )

        HistoryRepository.deleteForUser(id)
        PlaylistRepository.deleteUserPlaylists(id)
        UserRepository.deleteUser(id)

        call.respond(HttpStatusCode.OK)
    }

    post("/api/admin/users/{id}/password") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = call.receive<ChangePasswordReq>()
        UserRepository.updatePasswordById(id, BCrypt.hashpw(req.newPassword, BCrypt.gensalt()))
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
    return UserRepository.isAdmin(username)
}
