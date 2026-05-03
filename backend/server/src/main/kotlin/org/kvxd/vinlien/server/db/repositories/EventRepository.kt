package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.PlayEvents
import org.kvxd.vinlien.server.db.SkipEvents
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.server.db.repositories.TrackRepository.toTrack
import org.kvxd.vinlien.shared.models.feed.PlayEventRequest
import org.kvxd.vinlien.shared.models.media.Track

object EventRepository {

    data class PlayEventEntry(
        val track: Track,
        val eventType: String,
        val playedMs: Long,
        val durationMs: Long,
        val wasManual: Boolean,
        val timestampMs: Long
    )

    data class SkipEventEntry(
        val trackId: String,
        val artist: String,
        val playedMs: Long,
        val timestampMs: Long
    )

    suspend fun recordPlayEvent(userId: String, req: PlayEventRequest) = dbQuery {
        TrackRepository.insertOrUpdateTrackInTransaction(req.track)
        PlayEvents.insert {
            it[this.userId] = userId
            it[trackId] = req.track.id
            it[eventType] = req.eventType.take(32)
            it[eventSource] = req.source?.take(32)
            it[sessionId] = req.sessionId?.take(64)
            it[playedMs] = req.playedMs.coerceAtLeast(0L)
            it[durationMs] = req.durationMs.coerceAtLeast(0L)
            it[wasManual] = req.wasManual
            it[timestamp] = System.currentTimeMillis()
        }
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

    suspend fun fetchPlayEvents(userId: String, limit: Int = 1000): List<PlayEventEntry> = dbQuery {
        (PlayEvents innerJoin Tracks)
            .selectAll()
            .where { PlayEvents.userId eq userId }
            .orderBy(PlayEvents.timestamp to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                PlayEventEntry(
                    track = row.toTrack(),
                    eventType = row[PlayEvents.eventType],
                    playedMs = row[PlayEvents.playedMs],
                    durationMs = row[PlayEvents.durationMs],
                    wasManual = row[PlayEvents.wasManual],
                    timestampMs = row[PlayEvents.timestamp]
                )
            }
    }

    suspend fun fetchSkips(userId: String): List<SkipEventEntry> = dbQuery {
        SkipEvents.selectAll()
            .where { SkipEvents.userId eq userId }
            .map { row ->
                SkipEventEntry(
                    trackId = row[SkipEvents.trackId],
                    artist = row[SkipEvents.artist],
                    playedMs = row[SkipEvents.playedMs],
                    timestampMs = row[SkipEvents.timestamp]
                )
            }
    }
}
