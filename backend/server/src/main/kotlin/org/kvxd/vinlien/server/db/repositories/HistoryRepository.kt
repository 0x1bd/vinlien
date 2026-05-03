package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.History
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.server.db.repositories.TrackRepository.toTrack
import org.kvxd.vinlien.shared.models.media.Track

object HistoryRepository {

    suspend fun insert(userId: String, track: Track) = dbQuery {
        TrackRepository.insertOrUpdateTrackInTransaction(track)
        History.insert {
            it[this.userId] = userId
            it[trackId] = track.id
            it[timestamp] = System.currentTimeMillis()
        }
    }

    suspend fun getRecentHistory(userId: String, limit: Int = 200): List<Pair<Track, Long>> = dbQuery {
        (History innerJoin Tracks)
            .selectAll()
            .where { History.userId eq userId }
            .orderBy(History.timestamp to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                Pair(row.toTrack(), row[History.timestamp])
            }
    }

    suspend fun deleteForUser(userId: String) = dbQuery {
        History.deleteWhere { History.userId eq userId }
    }
}
