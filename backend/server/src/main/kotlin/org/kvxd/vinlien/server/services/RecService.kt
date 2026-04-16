package org.kvxd.vinlien.server.services

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery

object RecService {

    data class RecProfile(
        val history: List<RecommendationEngine.HistoryEntry>,
        val skips: List<RecommendationEngine.SkipEntry>,
        val recentTrackIds: List<String>,
        val seenFingerprints: Set<String>
    )

    data class BaseProfile(
        val history: List<RecommendationEngine.HistoryEntry>,
        val skips: List<RecommendationEngine.SkipEntry>,
        val recentTrackIds: List<String>
    )

    suspend fun buildRecProfile(userId: String, queueTitles: List<String>): RecProfile = dbQuery {
        val historyRows = fetchHistory(userId)
        val skipRows = fetchSkips(userId)
        val recentTrackIds = historyRows.take(30).map { it.trackId }

        val dislikedPlaylistId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
            .singleOrNull()?.get(Playlists.id)

        val dislikedTitles = if (dislikedPlaylistId != null) {
            (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq dislikedPlaylistId }
                .map { it[Tracks.title] }
        } else emptyList()

        val recentTitles = if (recentTrackIds.isNotEmpty()) {
            Tracks.selectAll()
                .where { Tracks.id inList recentTrackIds }
                .map { it[Tracks.title] }
        } else emptyList()

        val seenFingerprints = (queueTitles + recentTitles + dislikedTitles)
            .map { TrackFingerprint.of(it) }
            .filter { it.isNotBlank() }
            .toSet()

        RecProfile(historyRows, skipRows, recentTrackIds, seenFingerprints)
    }

    suspend fun buildBaseProfile(userId: String): BaseProfile = dbQuery {
        val historyRows = fetchHistory(userId)
        val skipRows = fetchSkips(userId)
        BaseProfile(historyRows, skipRows, historyRows.take(30).map { it.trackId })
    }

    suspend fun recordSkip(userId: String, trackId: String, artist: String, playedMs: Long) = dbQuery {
        SkipEvents.insert {
            it[this.userId] = userId
            it[this.trackId] = trackId
            it[this.artist] = artist
            it[this.playedMs] = playedMs
            it[timestamp] = System.currentTimeMillis()
        }
    }

    private fun fetchHistory(userId: String): List<RecommendationEngine.HistoryEntry> =
        (History innerJoin Tracks)
            .selectAll()
            .where { History.userId eq userId }
            .orderBy(History.timestamp to SortOrder.DESC)
            .limit(500)
            .map { row ->
                RecommendationEngine.HistoryEntry(
                    trackId = row[Tracks.id],
                    artist = row[Tracks.artist],
                    timestampMs = row[History.timestamp]
                )
            }

    private fun fetchSkips(userId: String): List<RecommendationEngine.SkipEntry> =
        SkipEvents.selectAll()
            .where { SkipEvents.userId eq userId }
            .map { row ->
                RecommendationEngine.SkipEntry(
                    trackId = row[SkipEvents.trackId],
                    artist = row[SkipEvents.artist],
                    playedMs = row[SkipEvents.playedMs]
                )
            }
}
