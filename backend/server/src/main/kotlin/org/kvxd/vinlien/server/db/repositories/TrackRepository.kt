package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.TrackFeatures
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.shared.models.media.Track

object TrackRepository {

    fun ResultRow.toTrack(): Track = Normalizer.normalizeTrack(
        Track(
            id = this[Tracks.id],
            title = this[Tracks.title],
            artist = this[Tracks.artist],
            durationMs = this[Tracks.durationMs],
            streamUrl = this[Tracks.streamUrl],
            artworkUrl = this[Tracks.artworkUrl],
            canonicalId = this[Tracks.canonicalId],
            lastFmUrl = this[Tracks.lastFmUrl],
            albumTitle = this[Tracks.albumTitle],
            albumId = this[Tracks.albumId]
        )
    )

    suspend fun insertOrUpdateTrack(track: Track) = dbQuery {
        insertOrUpdateTrackInTransaction(track)
    }

    fun insertOrUpdateTrackInTransaction(track: Track) {
        val exists = Tracks.selectAll().where { Tracks.id eq track.id }.count() > 0
        if (!exists) {
            Tracks.insert {
                it[id] = track.id
                it[title] = track.title
                it[artist] = track.artist
                it[durationMs] = track.durationMs
                it[streamUrl] = track.streamUrl
                it[artworkUrl] = track.artworkUrl
                it[canonicalId] = track.canonicalId
                it[lastFmUrl] = track.lastFmUrl
                it[albumTitle] = track.albumTitle
                it[albumId] = track.albumId
            }
        } else {
            Tracks.update({ Tracks.id eq track.id }) {
                it[title] = track.title
                it[artist] = track.artist
                it[durationMs] = track.durationMs
                it[streamUrl] = track.streamUrl
                if (track.artworkUrl != null) it[artworkUrl] = track.artworkUrl
                if (track.canonicalId != null) it[canonicalId] = track.canonicalId
                if (track.lastFmUrl != null) it[lastFmUrl] = track.lastFmUrl
                if (track.albumTitle != null) it[albumTitle] = track.albumTitle
                if (track.albumId != null) it[albumId] = track.albumId
            }
        }
    }

    suspend fun getFeatures(trackId: String): String? = dbQuery {
        TrackFeatures.selectAll().where { TrackFeatures.trackId eq trackId }
            .singleOrNull()?.get(TrackFeatures.features)
    }

    suspend fun updateFeatures(trackId: String, featuresJson: String) = dbQuery {
        val exists = TrackFeatures.selectAll().where { TrackFeatures.trackId eq trackId }.count() > 0
        if (exists) {
            TrackFeatures.update({ TrackFeatures.trackId eq trackId }) {
                it[features] = featuresJson
                it[updatedAt] = System.currentTimeMillis()
            }
        } else {
            TrackFeatures.insert {
                it[this.trackId] = trackId
                it[features] = featuresJson
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    suspend fun getArtworkUrl(trackId: String): String? = dbQuery {
        Tracks.selectAll().where { Tracks.id eq trackId }
            .map { it[Tracks.artworkUrl] }
            .firstOrNull()
    }

    suspend fun updateArtworkUrl(trackId: String, url: String) = dbQuery {
        Tracks.update({ Tracks.id eq trackId }) {
            it[Tracks.artworkUrl] = url
        }
    }
}
