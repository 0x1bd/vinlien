package org.kvxd.vinlien.server.services

import org.kvxd.vinlien.server.db.History
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.server.db.repositories.StatsRepository
import org.kvxd.vinlien.shared.models.admin.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AdminStatsService {

    suspend fun computeStats(): AdminStatsResponse {
        val pendingUsers = StatsRepository.getPendingUsers()
        val allHistoryRows = StatsRepository.getAllHistoryRows()
        
        val totalPlays = allHistoryRows.size
        val uniqueTracks = allHistoryRows.distinctBy { it[History.trackId] }.size

        val userNames = StatsRepository.getUserNamesMap()

        val historyByUser = allHistoryRows.groupBy { it[History.userId] }
        var totalPlaytimeMs = 0L
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

        val zone = try {
            val tz = System.getenv("TZ")
            if (!tz.isNullOrBlank()) ZoneId.of(tz) else ZoneId.of("UTC")
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }
        val dayFmt = DateTimeFormatter.ofPattern("MM/dd")
        val today = LocalDate.now(zone)
        val playsLast7Days = (6 downTo 0).map { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val count = allHistoryRows.count { it[History.timestamp] in dayStart until dayEnd }
            DayStat(day.format(dayFmt), count)
        }

        val peakHour = allHistoryRows
            .groupBy { Instant.ofEpochMilli(it[History.timestamp]).atZone(zone).hour }
            .maxByOrNull { it.value.size }?.key ?: 0

        val avgPlaysPerUser = if (userNames.isNotEmpty()) totalPlays.toDouble() / userNames.size else 0.0

        return AdminStatsResponse(
            stats = AdminStats(
                userNames.size, totalPlays, uniqueTracks, totalPlaytimeMs,
                topUsers, topTracks, topArtists, playsLast7Days, peakHour, avgPlaysPerUser
            ),
            pending = pendingUsers
        )
    }
}
