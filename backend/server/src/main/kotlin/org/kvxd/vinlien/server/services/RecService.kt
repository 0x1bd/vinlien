package org.kvxd.vinlien.server.services

import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.db.repositories.EventRepository
import org.kvxd.vinlien.server.db.repositories.RecRepository
import org.kvxd.vinlien.server.db.repositories.TasteRepository
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

    private val cache = TtlCache<String, UserProfile>(ttlMs = 2 * 60_000L, maxSize = 500)

    suspend fun getProfile(userId: String, queueTitles: List<String> = emptyList()): UserProfile {
        val base = cache.get(userId) ?: buildProfile(userId).also { cache.put(userId, it) }
        val queueFps = queueTitles.map { TrackFingerprint.of(it) }.filter { it.isNotBlank() }.toSet()
        return if (queueFps.isEmpty()) base
        else base.copy(seenFingerprints = base.seenFingerprints + queueFps)
    }

    fun invalidate(userId: String) = cache.remove(userId)

    suspend fun recordPlayEvent(userId: String, req: PlayEventRequest) {
        EventRepository.recordPlayEvent(userId, req)
        RecRepository.upsertTrackFeatures(req.track, TasteGraph.serializeFeatures(TasteGraph.extractFeatures(req.track)))
        invalidate(userId)
    }

    suspend fun recordSkip(userId: String, trackId: String, artist: String, playedMs: Long) {
        EventRepository.recordSkip(userId, trackId, artist, playedMs)
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

        return RecRepository.buildHomeSeeds(seedIds)
    }

    private suspend fun buildProfile(userId: String): UserProfile {
        val timedHistory = RecRepository.fetchTimedHistory(userId)
        val history = timedHistory.map {
            RecommendationEngine.HistoryEntry(
                trackId = it.track.id,
                artist = it.track.artist,
                timestampMs = it.timestampMs
            )
        }
        
        val skips = EventRepository.fetchSkips(userId).map {
            RecommendationEngine.SkipEntry(
                trackId = it.trackId,
                artist = it.artist,
                playedMs = it.playedMs,
                timestampMs = it.timestampMs
            )
        }
        
        val recentTracks = timedHistory.take(40).map { it.track }
        val recentTrackIds = recentTracks.flatMap { listOfNotNull(it.id, it.canonicalId) }.toSet()
        val recentCanonicalIds = recentTracks.mapNotNull { it.canonicalId }.toSet()
        
        val playlistTracks = RecRepository.fetchPlaylistTracks(userId)
        val playEvents = EventRepository.fetchPlayEvents(userId)

        val (likedTrackIds, likedArtists) = RecRepository.getLikedTracksIdsAndArtists(userId)
        val dislikedTracks = RecRepository.getDislikedTracks(userId)

        val dislikedTrackIds = dislikedTracks.flatMap { listOfNotNull(it.id, it.canonicalId) }.toSet()
        val dislikedArtists = dislikedTracks.map { it.artist.normArtist() }.toSet()

        val seenTitles = buildList<String> {
            if (recentTrackIds.isNotEmpty()) {
                addAll(RecRepository.getTrackTitles(recentTrackIds))
            }
            addAll(dislikedTracks.map { it.title })
        }

        val seenFingerprints = seenTitles
            .map { TrackFingerprint.of(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val tasteSignals = buildTasteSignals(timedHistory, playlistTracks, playEvents, dislikedTrackIds)
        tasteSignals.map { it.track }.distinctBy { it.id }.forEach { track ->
            RecRepository.upsertTrackFeatures(track, TasteGraph.serializeFeatures(TasteGraph.extractFeatures(track)))
        }
        
        val tasteCapsules = TasteGraph.buildCapsules(tasteSignals)
        TasteRepository.persistTasteCapsules(userId, tasteCapsules)

        return UserProfile(
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

    private fun buildTasteSignals(
        history: List<RecRepository.TimedTrack>,
        playlistTracks: List<RecRepository.PlaylistTrackEntry>,
        playEvents: List<EventRepository.PlayEventEntry>,
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
}
