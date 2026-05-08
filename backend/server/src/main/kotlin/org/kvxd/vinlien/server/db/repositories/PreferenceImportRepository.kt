package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.kvxd.vinlien.server.TasteGraph
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.History
import org.kvxd.vinlien.server.db.PlayEvents
import org.kvxd.vinlien.server.db.TrackFeatures
import org.kvxd.vinlien.shared.models.media.Track

object PreferenceImportRepository {

    data class ImportPlay(
        val track: Track,
        val playedMs: Long,
        val durationMs: Long,
        val timestampMs: Long,
        val source: String
    )

    suspend fun insertImportedPlays(userId: String, plays: List<ImportPlay>): Int = dbQuery {
        val now = System.currentTimeMillis()
        var insertedCount = 0
        plays.forEach { play ->
            TrackRepository.insertOrUpdateTrackInTransaction(play.track)

            val alreadyImported = PlayEvents.selectAll()
                .where {
                    (PlayEvents.userId eq userId) and
                            (PlayEvents.trackId eq play.track.id) and
                            (PlayEvents.eventType eq "imported") and
                            (PlayEvents.timestamp eq play.timestampMs)
                }
                .any()

            if (!alreadyImported) {
                History.insert {
                    it[this.userId] = userId
                    it[trackId] = play.track.id
                    it[timestamp] = play.timestampMs
                }

                PlayEvents.insert {
                    it[this.userId] = userId
                    it[trackId] = play.track.id
                    it[eventType] = "imported"
                    it[eventSource] = play.source.take(32)
                    it[sessionId] = null
                    it[playedMs] = play.playedMs.coerceAtLeast(0L)
                    it[durationMs] = play.durationMs.coerceAtLeast(0L)
                    it[wasManual] = false
                    it[timestamp] = play.timestampMs
                }
                insertedCount++
            }

            val features = TasteGraph.serializeFeatures(TasteGraph.extractFeatures(play.track))
            val exists = TrackFeatures.selectAll()
                .where { TrackFeatures.trackId eq play.track.id }
                .any()
            if (exists) {
                TrackFeatures.update({ TrackFeatures.trackId eq play.track.id }) {
                    it[this.features] = features
                    it[updatedAt] = now
                }
            } else {
                TrackFeatures.insert {
                    it[trackId] = play.track.id
                    it[this.features] = features
                    it[updatedAt] = now
                }
            }
        }
        insertedCount
    }
}