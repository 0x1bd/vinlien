package org.kvxd.vinlien.server.services

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.shared.models.feed.PlayEventRequest
import org.kvxd.vinlien.shared.models.media.Track
import kotlin.math.exp
import kotlin.math.ln

object RecService {

    data class UserProfile(
        val history: List<RecommendationEngine.HistoryEntry>,
        val skips: List<RecommendationEngine.SkipEntry>,
        val recentTrackIds: Set<String>,
        val recentCanonicalIds: Set<String>,
        val seenFingerprints: Set<String>,
        val likedArtists: Set<String>,
        val likedTrackIds: Set<String>,
        val dislikedTrackIds: Set<String>,
        val dislikedArtists: Set<String>,
        val tasteSignals: List<TasteTrackSignal>,
        val tasteCapsules: List<TasteCapsuleModel>
    )

    private data class TimedTrack(val track: Track, val timestampMs: Long)
    private data class PlaylistTrackEntry(val playlistName: String, val track: Track)
    private data class PlayEventEntry(
        val track: Track,
        val eventType: String,
        val playedMs: Long,
        val durationMs: Long,
        val wasManual: Boolean,
        val timestampMs: Long
    )

    private val cache = TtlCache<String, UserProfile>(ttlMs = 2 * 60_000L, maxSize = 500)

    suspend fun getProfile(userId: String, queueTitles: List<String> = emptyList()): UserProfile {
        val base = cache.get(userId) ?: buildProfile(userId).also { cache.put(userId, it) }
        val queueFps = queueTitles.map { TrackFingerprint.of(it) }.filter { it.isNotBlank() }.toSet()
        return if (queueFps.isEmpty()) base
        else base.copy(seenFingerprints = base.seenFingerprints + queueFps)
    }

    fun invalidate(userId: String) = cache.remove(userId)

    suspend fun recordPlayEvent(userId: String, req: PlayEventRequest) {
        dbQuery {
            DatabaseFactory.insertOrUpdateTrack(req.track)
            upsertTrackFeatures(req.track)
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
        invalidate(userId)
    }

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

    fun representativeTasteSeeds(
        profile: UserProfile,
        contextTracks: List<Track>,
        count: Int = 6
    ): List<Track> {
        if (profile.tasteSignals.isEmpty() || profile.tasteCapsules.isEmpty()) return emptyList()

        val activeCapsules = TasteGraph.activeCapsules(profile.tasteCapsules, contextTracks, limit = 3)
        val blockedIds = profile.recentTrackIds + contextTracks.map { it.id }
        val blockedFingerprints = contextTracks.map { TrackFingerprint.of(it.title) }.toSet()

        return profile.tasteSignals
            .asSequence()
            .filter { it.track.id !in blockedIds }
            .filter { it.track.id !in profile.dislikedTrackIds }
            .filter { TrackFingerprint.of(it.track.title) !in blockedFingerprints }
            .sortedByDescending { signal ->
                val capsuleFit = TasteGraph.capsuleFit(signal.track, activeCapsules)
                val freshnessPenalty = if (signal.track.id in profile.recentTrackIds) 1.0 else 0.0
                signal.weight * 0.35 + capsuleFit * 5.0 - freshnessPenalty
            }
            .map { it.track }
            .distinctBy { (it.canonicalId ?: it.id) + "|" + TrackFingerprint.of(it.title) }
            .take(count)
            .toList()
    }

    fun tasteSearchQueries(profile: UserProfile, contextTracks: List<Track>, seedTrack: Track, limit: Int = 8): List<String> {
        val activeCapsules = TasteGraph.activeCapsules(profile.tasteCapsules, contextTracks, limit = 3)
        return TasteGraph.searchQueries(activeCapsules, seedTrack, limit)
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
        val timedHistory = fetchTimedHistory(userId)
        val history = timedHistory.map {
            RecommendationEngine.HistoryEntry(
                trackId = it.track.id,
                artist = it.track.artist,
                timestampMs = it.timestampMs
            )
        }
        val skips = fetchSkips(userId)
        val recentTracks = timedHistory.take(40).map { it.track }
        val recentTrackIds = recentTracks.flatMap { listOfNotNull(it.id, it.canonicalId) }.toSet()
        val recentCanonicalIds = recentTracks.mapNotNull { it.canonicalId }.toSet()
        val playlistTracks = fetchPlaylistTracks(userId)
        val playEvents = fetchPlayEvents(userId)

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
        val likedTrackIds = if (likedId != null) {
            (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq likedId }
                .flatMap { row -> listOfNotNull(row[Tracks.id], row[Tracks.canonicalId]) }
                .toSet()
        } else emptySet()

        val dislikedId = Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq "Disliked Songs") }
            .singleOrNull()?.get(Playlists.id)

        val dislikedTracks = if (dislikedId != null) {
            (PlaylistTracks innerJoin Tracks)
                .selectAll()
                .where { PlaylistTracks.playlistId eq dislikedId }
                .map { it.toTrack() }
        } else emptyList()

        val dislikedTrackIds = dislikedTracks.flatMap { listOfNotNull(it.id, it.canonicalId) }.toSet()
        val dislikedArtists = dislikedTracks.map { it.artist.normArtist() }.toSet()

        val seenTitles = buildList<String> {
            if (recentTrackIds.isNotEmpty()) {
                addAll(Tracks.selectAll().where { Tracks.id inList recentTrackIds }.map { it[Tracks.title] })
            }
            addAll(dislikedTracks.map { it.title })
        }

        val seenFingerprints = seenTitles
            .map { TrackFingerprint.of(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val tasteSignals = buildTasteSignals(timedHistory, playlistTracks, playEvents, dislikedTrackIds)
        tasteSignals.map { it.track }.distinctBy { it.id }.forEach(::upsertTrackFeatures)
        val tasteCapsules = TasteGraph.buildCapsules(tasteSignals)
        persistTasteCapsules(userId, tasteCapsules)

        UserProfile(
            history = history,
            skips = skips,
            recentTrackIds = recentTrackIds,
            recentCanonicalIds = recentCanonicalIds,
            seenFingerprints = seenFingerprints,
            likedArtists = likedArtists,
            likedTrackIds = likedTrackIds,
            dislikedTrackIds = dislikedTrackIds,
            dislikedArtists = dislikedArtists,
            tasteSignals = tasteSignals,
            tasteCapsules = tasteCapsules
        )
    }

    private fun fetchTimedHistory(userId: String): List<TimedTrack> =
        (History innerJoin Tracks)
            .selectAll()
            .where { History.userId eq userId }
            .orderBy(History.timestamp to SortOrder.DESC)
            .limit(500)
            .map { row -> TimedTrack(row.toTrack(), row[History.timestamp]) }

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

    private fun fetchPlaylistTracks(userId: String): List<PlaylistTrackEntry> =
        (Playlists innerJoin PlaylistTracks innerJoin Tracks)
            .selectAll()
            .where { Playlists.userId eq userId }
            .map { row -> PlaylistTrackEntry(row[Playlists.name], row.toTrack()) }

    private fun fetchPlayEvents(userId: String): List<PlayEventEntry> =
        (PlayEvents innerJoin Tracks)
            .selectAll()
            .where { PlayEvents.userId eq userId }
            .orderBy(PlayEvents.timestamp to SortOrder.DESC)
            .limit(1000)
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

    private fun buildTasteSignals(
        history: List<TimedTrack>,
        playlistTracks: List<PlaylistTrackEntry>,
        playEvents: List<PlayEventEntry>,
        dislikedTrackIds: Set<String>
    ): List<TasteTrackSignal> {
        val now = System.currentTimeMillis()
        val signals = mutableListOf<TasteTrackSignal>()

        fun add(track: Track, weight: Double, timestampMs: Long, source: String) {
            val ids = listOfNotNull(track.id, track.canonicalId)
            if (ids.any { it in dislikedTrackIds }) return
            if (weight <= 0.0) return
            signals.add(TasteTrackSignal(track, weight, timestampMs, source))
        }

        val historyCounts = history.groupingBy { it.track.id }.eachCount()
        history.forEach { entry ->
            val ageWeight = exp(-ln(2.0) * ((now - entry.timestampMs).coerceAtLeast(0L) / (45.0 * 86_400_000.0)))
            val repeatBoost = ln(1.0 + (historyCounts[entry.track.id] ?: 1).toDouble()) * 0.45
            add(entry.track, 0.9 * ageWeight + repeatBoost, entry.timestampMs, "history")
        }

        playlistTracks.forEach { entry ->
            val weight = when (entry.playlistName) {
                "Liked Songs" -> 6.0
                "Disliked Songs" -> 0.0
                else -> 2.6
            }
            add(entry.track, weight, now, "playlist:${entry.playlistName}")
        }

        playEvents.forEach { event ->
            val completion = when {
                event.durationMs > 0L -> (event.playedMs.toDouble() / event.durationMs).coerceIn(0.0, 1.0)
                event.playedMs >= 180_000L -> 1.0
                event.playedMs >= 45_000L -> 0.55
                else -> 0.0
            }
            val weight = when (event.eventType) {
                "completed" -> 3.5
                "advanced" -> 1.4 + completion * 2.0
                "started" -> 0.25
                "skip_requested" -> if (completion >= 0.55) 0.7 else 0.0
                else -> completion
            }
            add(event.track, weight, event.timestampMs, "event:${event.eventType}")
        }

        return signals
            .groupBy { it.track.id }
            .map { (_, grouped) ->
                val first = grouped.maxBy { it.timestampMs }
                first.copy(
                    weight = grouped.sumOf { it.weight }.coerceAtMost(14.0),
                    source = grouped.maxBy { it.weight }.source
                )
            }
            .sortedByDescending { it.weight }
    }

    private fun upsertTrackFeatures(track: Track) {
        val raw = TasteGraph.serializeFeatures(TasteGraph.extractFeatures(track))
        val now = System.currentTimeMillis()
        val exists = TrackFeatures.selectAll().where { TrackFeatures.trackId eq track.id }.any()
        if (exists) {
            TrackFeatures.update({ TrackFeatures.trackId eq track.id }) {
                it[features] = raw
                it[updatedAt] = now
            }
        } else {
            TrackFeatures.insert {
                it[trackId] = track.id
                it[features] = raw
                it[updatedAt] = now
            }
        }
    }

    private fun persistTasteCapsules(userId: String, capsules: List<TasteCapsuleModel>) {
        TasteCapsules.deleteWhere { TasteCapsules.userId eq userId }
        val now = System.currentTimeMillis()
        capsules.forEach { capsule ->
            TasteCapsules.insert {
                it[this.userId] = userId
                it[capsuleKey] = capsule.key
                it[label] = capsule.label.take(120)
                it[features] = TasteGraph.serializeFeatures(capsule.features)
                it[weight] = capsule.weight
                it[updatedAt] = now
            }
        }
    }
}
