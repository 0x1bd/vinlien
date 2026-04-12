package org.kvxd.vinlien.server.routes

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.shared.models.AdminStats
import org.kvxd.vinlien.shared.models.AdminStatsResponse
import org.kvxd.vinlien.shared.models.ChangePasswordReq
import org.kvxd.vinlien.shared.models.DayStat
import org.kvxd.vinlien.shared.models.TrackStat
import org.kvxd.vinlien.shared.models.User
import org.kvxd.vinlien.shared.models.UserStat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.mindrot.jbcrypt.BCrypt

fun Route.adminRoutes() {

    suspend fun isRequesterAdmin(call: ApplicationCall): Boolean {
        val username = call.principal<JWTPrincipal>()?.payload?.getClaim("username")?.asString() ?: return false
        return dbQuery {
            Users.selectAll().where { (Users.username eq username) and (Users.role eq "ADMIN") }.count() > 0
        }
    }

    get("/api/admin/stats") {
        if (!isRequesterAdmin(call)) return@get call.respond(HttpStatusCode.Forbidden)

        val stats = dbQuery {
            val pendingUsers = Users.selectAll().where { Users.role eq "PENDING" }.map {
                User(it[Users.id], it[Users.username], it[Users.role])
            }

            val allHistoryRows = (History innerJoin Tracks).selectAll().toList()
            val totalPlays = allHistoryRows.size
            val uniqueTracks = allHistoryRows.distinctBy { it[History.trackId] }.size
            var totalPlaytimeMs = 0L

            val userNames = Users.selectAll().associate { it[Users.id] to it[Users.username] }

            val historyByUser = allHistoryRows.groupBy { it[History.userId] }
            for ((_, userHistory) in historyByUser) {
                val sorted = userHistory.sortedBy { it[History.timestamp] }
                for (i in sorted.indices) {
                    val durationMs = sorted[i][Tracks.durationMs]
                    val duration = if (durationMs > 0) durationMs else 180_000L
                    val timeDiff = if (i < sorted.size - 1) {
                        sorted[i + 1][History.timestamp] - sorted[i][History.timestamp]
                    } else {
                        System.currentTimeMillis() - sorted[i][History.timestamp]
                    }
                    totalPlaytimeMs += minOf(duration, timeDiff)
                }
            }

            val topUsers = historyByUser.map { (uid, entries) ->
                UserStat(userNames[uid] ?: "Unknown", entries.size)
            }.sortedByDescending { it.playCount }.take(5)

            val topTracks = allHistoryRows
                .groupBy { it[History.trackId] }
                .map { (_, entries) ->
                    val row = entries.first()
                    TrackStat(row[Tracks.title], row[Tracks.artist], entries.size)
                }
                .sortedByDescending { it.playCount }
                .take(5)

            val topArtists = allHistoryRows
                .groupBy { it[Tracks.artist] }
                .map { (artist, entries) -> UserStat(artist, entries.size) }
                .sortedByDescending { it.playCount }
                .take(5)

            val zone = ZoneId.systemDefault()
            val dayFmt = DateTimeFormatter.ofPattern("MM/dd").withZone(zone)
            val nowMs = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val playsLast7Days = (6 downTo 0).map { daysAgo ->
                val dayStart = nowMs - (daysAgo + 1) * dayMs
                val dayEnd = nowMs - daysAgo * dayMs
                val count = allHistoryRows.count { it[History.timestamp] in dayStart until dayEnd }
                DayStat(dayFmt.format(Instant.ofEpochMilli(dayEnd - 1)), count)
            }

            val peakHour = allHistoryRows
                .groupBy { Instant.ofEpochMilli(it[History.timestamp]).atZone(zone).hour }
                .maxByOrNull { it.value.size }?.key ?: 0

            val avgPlaysPerUser = if (userNames.isNotEmpty()) totalPlays.toDouble() / userNames.size else 0.0

            AdminStatsResponse(
                stats = AdminStats(
                    userNames.size, totalPlays, uniqueTracks, totalPlaytimeMs,
                    topUsers, topTracks, topArtists, playsLast7Days, peakHour, avgPlaysPerUser
                ),
                pending = pendingUsers
            )
        }
        call.respond(stats)
    }

    post("/api/admin/approve/{id}") {
        if (!isRequesterAdmin(call)) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        dbQuery {
            Users.update({ Users.id eq id }) { it[role] = "APPROVED" }
        }
        call.respond(HttpStatusCode.OK)
    }

    get("/api/admin/users") {
        if (!isRequesterAdmin(call)) return@get call.respond(HttpStatusCode.Forbidden)
        val usersList = dbQuery {
            Users.selectAll().map {
                User(it[Users.id], it[Users.username], it[Users.role])
            }
        }
        call.respond(usersList)
    }

    delete("/api/admin/users/{id}") {
        if (!isRequesterAdmin(call)) return@delete call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val currentUserId = call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()

        if (id == currentUserId) {
            return@delete call.respond(HttpStatusCode.BadRequest, "Cannot delete your own admin account")
        }

        dbQuery {
            History.deleteWhere { History.userId eq id }
            val playlistIds = Playlists.selectAll()
                .where { Playlists.userId eq id }
                .map { it[Playlists.id] }
            if (playlistIds.isNotEmpty()) {
                PlaylistTracks.deleteWhere { PlaylistTracks.playlistId inList playlistIds }
            }
            Playlists.deleteWhere { Playlists.userId eq id }
            Users.deleteWhere { Users.id eq id }
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/admin/users/{id}/password") {
        if (!isRequesterAdmin(call)) return@post call.respond(HttpStatusCode.Forbidden)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = call.receive<ChangePasswordReq>()

        dbQuery {
            Users.update({ Users.id eq id }) {
                it[passwordHash] = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
            }
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/api/admin/cache/clear") {
        if (!isRequesterAdmin(call)) return@post call.respond(HttpStatusCode.Forbidden)
        searchCache.clear()
        trendingCache.clear()
        call.respond(HttpStatusCode.OK)
    }
}