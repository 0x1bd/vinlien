package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.History
import org.kvxd.vinlien.server.db.PlaylistTracks
import org.kvxd.vinlien.server.db.Playlists
import org.kvxd.vinlien.server.db.TrackFeatures
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.server.db.repositories.TrackRepository.toTrack
import org.kvxd.vinlien.shared.models.media.Track

object RecRepository {

    data class TimedTrack(val track: Track, val timestampMs: Long)
    data class PlaylistTrackEntry(val playlistName: String, val track: Track)

    suspend fun fetchTimedHistory(userId: String): List<TimedTrack> = dbQuery {
        (History innerJoin Tracks)
            .selectAll()
            .where { History.userId eq userId }
            .orderBy(History.timestamp to SortOrder.DESC)
            .limit(500)
            .map { row -> TimedTrack(row.toTrack(), row[History.timestamp]) }
    }

    suspend fun fetchPlaylistTracks(userId: String): List<PlaylistTrackEntry> = dbQuery {
        (Playlists innerJoin PlaylistTracks innerJoin Tracks)
            .selectAll()
            .where { Playlists.userId eq userId }
            .map { row -> PlaylistTrackEntry(row[Playlists.name], row.toTrack()) }
    }

    suspend fun getLikedTracksIdsAndArtists(userId: String): Pair<Set<String>, Set<String>> = dbQuery {
        val likedId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Liked Songs") }
            .singleOrNull()?.get(Playlists.id)

        if (likedId != null) {
            val rows = (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq likedId }
            val artists = rows.map { it[Tracks.artist].lowercase().trim() }.toSet()
            val ids = rows.flatMap { listOfNotNull(it[Tracks.id], it[Tracks.canonicalId]) }.toSet()
            Pair(ids, artists)
        } else {
            Pair(emptySet(), emptySet())
        }
    }

    suspend fun getDislikedTracks(userId: String): List<Track> = dbQuery {
        val dislikedId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
            .singleOrNull()?.get(Playlists.id)

        if (dislikedId != null) {
            (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq dislikedId }
                .map { it.toTrack() }
        } else {
            emptyList()
        }
    }

    suspend fun getTrackTitles(trackIds: Set<String>): List<String> = dbQuery {
        if (trackIds.isEmpty()) return@dbQuery emptyList()
        Tracks.selectAll().where { Tracks.id inList trackIds }.map { it[Tracks.title] }
    }

    suspend fun buildHomeSeeds(seedIds: List<String>): List<Track> = dbQuery {
        if (seedIds.isEmpty()) return@dbQuery emptyList()
        Tracks.selectAll()
            .where { Tracks.id inList seedIds }
            .map { it.toTrack() }
    }

    suspend fun upsertTrackFeatures(track: Track, featuresJson: String) = dbQuery {
        val now = System.currentTimeMillis()
        val exists = TrackFeatures.selectAll().where { TrackFeatures.trackId eq track.id }.any()
        if (exists) {
            TrackFeatures.update({ TrackFeatures.trackId eq track.id }) {
                it[features] = featuresJson
                it[updatedAt] = now
            }
        } else {
            TrackFeatures.insert {
                it[trackId] = track.id
                it[features] = featuresJson
                it[updatedAt] = now
            }
        }
    }
}
