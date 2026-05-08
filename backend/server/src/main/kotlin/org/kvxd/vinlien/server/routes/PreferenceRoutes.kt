package org.kvxd.vinlien.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.services.PreferenceImportService
import org.kvxd.vinlien.shared.models.preferences.PreferenceImportRequest

fun Route.preferenceRoutes() {
    post("/api/preferences/import") {
        val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val req = call.receive<PreferenceImportRequest>()

        if (req.content.length > 25 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, "Import files are limited to 25 MB.")
        }

        val result = PreferenceImportService.importPreferences(userId, req)
        call.respond(result)
    }
}