package org.kvxd.vinlien.server.services

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.shared.models.media.Track

object RecService {

    data class UserProfile(
        val history: List<RecommendationEngine.HistoryEntry>,
        val skips: List<RecommendationEngine.SkipEntry>,
        val recentTrackIds: Set<String>,
        val seenFingerprints: Set<String>,
        val likedArtists: Set<String>
    )

    private val cache = TtlCache<String, UserProfile>(ttlMs = 2 * 60_000L, maxSize = 500)

    suspend fun getProfile(userId: String, queueTitles: List<String> = emptyList()): UserProfile {
        val base = cache.get(userId) ?: buildProfile(userId).also { cache.put(userId, it) }
        val queueFps = queueTitles.map { TrackFingerprint.of(it) }.filter { it.isNotBlank() }.toSet()
        return if (queueFps.isEmpty()) base
        else base.copy(seenFingerprints = base.seenFingerprints + queueFps)
    }

    fun invalidate(userId: String) = cache.remove(userId)

    suspend fun recordSkip(userId: String, trackId: String, artist: String, playedMs: Long) {
        dbQuery {
            SkipEvents.insert {
                it[this.userId] = userId
                it[this.trackId] = trackId
                it[this.artist] = artist
                it[this.playedMs] = playedMs
                it[timestamp] = System.currentTimeMillis()
            }
        }
        invalidate(userId)
    }

    suspend fun buildHomeSeeds(
        history: List<RecommendationEngine.HistoryEntry>,
        vector: RecommendationEngine.ListeningVector,
        count: Int = 6
    ): List<Track> {
        if (vector.artistScores.isEmpty() || history.isEmpty()) return emptyList()

        val artistPool = vector.artistScores.entries
            .sortedByDescending { it.value }
            .take(count * 3)
            .shuffled()

        val seen = mutableSetOf<String>()
        val seedIds = mutableListOf<String>()
        for ((artist, _) in artistPool) {
            if (seedIds.size >= count) break
            if (artist in seen) continue
            val trackId = history.firstOrNull { it.artist.normArtist() == artist }?.trackId ?: continue
            seen.add(artist)
            seedIds.add(trackId)
        }

        if (seedIds.isEmpty()) return emptyList()

        return dbQuery {
            Tracks.selectAll()
                .where { Tracks.id inList seedIds }
                .map { it.toTrack() }
        }
    }

    private suspend fun buildProfile(userId: String): UserProfile = dbQuery {
        val history = fetchHistory(userId)
        val skips = fetchSkips(userId)
        val recentTrackIds = history.take(30).map { it.trackId }.toSet()

        val likedId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Liked Songs") }
            .singleOrNull()?.get(Playlists.id)

        val likedArtists = if (likedId != null) {
            (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq likedId }
                .map { it[Tracks.artist].normArtist() }
                .toSet()
        } else emptySet()

        val dislikedId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
            .singleOrNull()?.get(Playlists.id)

        val seenTitles = buildList<String> {
            if (recentTrackIds.isNotEmpty()) {
                addAll(Tracks.selectAll().where { Tracks.id inList recentTrackIds }.map { it[Tracks.title] })
            }
            if (dislikedId != null) {
                addAll((PlaylistTracks innerJoin Tracks)
                    .selectAll()
                    .where { PlaylistTracks.playlistId eq dislikedId }
                    .map { it[Tracks.title] })
            }
        }

        val seenFingerprints = seenTitles
            .map { TrackFingerprint.of(it) }
            .filter { it.isNotBlank() }
            .toSet()

        UserProfile(history, skips, recentTrackIds, seenFingerprints, likedArtists)
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
                    playedMs = row[SkipEvents.playedMs],
                    timestampMs = row[SkipEvents.timestamp]
                )
            }
}
