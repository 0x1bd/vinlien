package org.kvxd.vinlien.server

import org.kvxd.vinlien.shared.models.feed.RecResult
import org.kvxd.vinlien.shared.models.media.Track
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

class RecommendationEngine {

    companion object {
        private const val TOP_FAMILIAR_N = 15
        private const val SKIP_FULL_WEIGHT_MS = 30_000.0
    }

    data class HistoryEntry(val trackId: String, val artist: String, val timestampMs: Long)
    data class SkipEntry(val trackId: String, val artist: String, val playedMs: Long, val timestampMs: Long)

    data class ListeningVector(
        val artistScores: Map<String, Double>,
        val likedArtistIds: Set<String>,
        val trackSkipScores: Map<String, Double>,
        val artistSkipScores: Map<String, Double>,
        val topFamiliarArtists: Set<String>
    )

    fun buildListeningVector(
        history: List<HistoryEntry>,
        skips: List<SkipEntry>,
        likedArtists: Set<String> = emptySet(),
        halfLifeDays: Int = 14,
        nowMs: Long = System.currentTimeMillis()
    ): ListeningVector {
        val decayConstant = ln(2.0) / (halfLifeDays * 86_400_000.0)

        val rawScores = mutableMapOf<String, Double>()
        for (entry in history) {
            val artist = entry.artist.normArtist()
            if (artist.isBlank()) continue
            val ageMs = (nowMs - entry.timestampMs).coerceAtLeast(0L).toDouble()
            rawScores[artist] = (rawScores[artist] ?: 0.0) + exp(-decayConstant * ageMs)
        }

        val maxScore = rawScores.values.maxOrNull() ?: 1.0
        val artistScores = rawScores.mapValues { it.value / maxScore }

        val trackSkipScores = mutableMapOf<String, Double>()
        val artistSkipScores = mutableMapOf<String, Double>()
        for (skip in skips) {
            val fraction = (skip.playedMs / SKIP_FULL_WEIGHT_MS).coerceIn(0.0, 1.0)
            val playWeight = max(0.0, 1.0 - fraction).let { it * it }
            val ageMs = (nowMs - skip.timestampMs).coerceAtLeast(0L).toDouble()
            val timeWeight = exp(-decayConstant * ageMs)
            val w = playWeight * timeWeight
            if (w <= 0.0) continue
            trackSkipScores[skip.trackId] = (trackSkipScores[skip.trackId] ?: 0.0) + w
            val artist = skip.artist.normArtist()
            if (artist.isNotBlank()) artistSkipScores[artist] = (artistSkipScores[artist] ?: 0.0) + w
        }

        val topFamiliarArtists = artistScores.entries
            .sortedByDescending { it.value }
            .take(TOP_FAMILIAR_N)
            .map { it.key }
            .toSet()

        return ListeningVector(artistScores, likedArtists, trackSkipScores, artistSkipScores, topFamiliarArtists)
    }

    fun scoreCandidate(
        track: Track,
        vector: ListeningVector,
        recentPlayedIds: Set<String>
    ): Double {
        val artist = track.artist.normArtist()
        val artistScore = vector.artistScores[artist] ?: 0.0

        val familiarityBonus = artistScore * 50.0
        val likedBonus = if (artist in vector.likedArtistIds) 15.0 * (1.0 - artistScore * 0.6) else 0.0
        val trackSkipPenalty = (vector.trackSkipScores[track.id] ?: 0.0) * 45.0
        val artistSkipPenalty = (vector.artistSkipScores[artist] ?: 0.0) * 15.0
        val recentPenalty = if (track.id in recentPlayedIds ||
                                (track.canonicalId != null && track.canonicalId in recentPlayedIds)) 60.0
                            else 0.0

        return familiarityBonus + likedBonus - trackSkipPenalty - artistSkipPenalty - recentPenalty
    }

    private fun sampleSoftmax(
        candidates: List<Pair<Track, Double>>,
        temperature: Float,
        blockedArtist: String? = null,
        forceNoveltyFrom: Set<String>? = null
    ): Track? {
        var pool = candidates.filter { (_, s) -> s.isFinite() }
        if (pool.isEmpty()) return null

        if (forceNoveltyFrom != null) {
            val novel = pool.filter { (t, _) -> t.artist.normArtist() !in forceNoveltyFrom }
            if (novel.isNotEmpty()) pool = novel
        }
        if (blockedArtist != null) {
            val different = pool.filter { (t, _) -> t.artist.normArtist() != blockedArtist }
            if (different.isNotEmpty()) pool = different
        }

        if (temperature <= 0f) return pool.maxByOrNull { it.second }?.first

        val maxScore = pool.maxOf { it.second }
        val weights = pool.map { (t, s) -> t to exp((s - maxScore) / temperature) }
        val total = weights.sumOf { it.second }
        if (total <= 0.0) return pool.maxByOrNull { it.second }?.first

        var rng = Math.random() * total
        for ((track, w) in weights) {
            rng -= w
            if (rng <= 0) return track
        }
        return weights.last().first
    }

    fun pickWithDiversity(
        candidates: List<Track>,
        vector: ListeningVector,
        recentPlayedIds: Set<String>,
        sessionArtists: List<String>,
        noveltyBudget: Float = 0.30f,
        maxConsecutiveSameArtist: Int = 3
    ): Pair<Track, String>? {
        if (candidates.isEmpty()) return null

        val scored = candidates.map { t -> t to scoreCandidate(t, vector, recentPlayedIds) }

        val lastN = sessionArtists.takeLast(maxConsecutiveSameArtist).map { it.normArtist() }
        val blockedArtist = run {
            val last = lastN.lastOrNull() ?: return@run null
            if (lastN.size >= maxConsecutiveSameArtist && lastN.all { it == last }) last else null
        }

        val forceNovelty = sessionArtists.size >= 3 && run {
            val familiarFraction = sessionArtists
                .count { it.normArtist() in vector.topFamiliarArtists }
                .toFloat() / sessionArtists.size
            familiarFraction > (1f - noveltyBudget)
        }

        val temperature = 8f + noveltyBudget * 27f

        val pick = sampleSoftmax(
            scored,
            temperature,
            blockedArtist,
            if (forceNovelty) vector.topFamiliarArtists else null
        ) ?: return null

        return pick to buildReason(pick, vector)
    }

    fun buildRadioQueue(
        candidates: List<Track>,
        vector: ListeningVector,
        recentPlayedIds: Set<String>,
        sessionArtists: List<String>,
        queueSize: Int = 10,
        noveltyBudget: Float = 0.30f,
        maxConsecutiveSameArtist: Int = 3
    ): List<RecResult> {
        val result = mutableListOf<RecResult>()
        val remaining = candidates.toMutableList()
        val currentSession = sessionArtists.toMutableList()

        while (result.size < queueSize && remaining.isNotEmpty()) {
            val pick = pickWithDiversity(
                remaining, vector, recentPlayedIds, currentSession, noveltyBudget, maxConsecutiveSameArtist
            ) ?: break
            result.add(RecResult(pick.first, pick.second))
            currentSession.add(pick.first.artist.normArtist())

            val pickedFp = TrackFingerprint.of(pick.first.title)
            val pickedArtist = pick.first.artist.normArtist()
            remaining.removeAll { t ->
                t.id == pick.first.id ||
                (TrackFingerprint.of(t.title) == pickedFp && t.artist.normArtist() == pickedArtist)
            }
        }

        return result
    }

    fun buildReason(track: Track, vector: ListeningVector): String {
        val artist = track.artist.normArtist()
        val score = vector.artistScores[artist] ?: 0.0
        return when {
            artist in vector.likedArtistIds && score >= 0.4 -> "From your favourites"
            score >= 0.75 -> "One of your top artists"
            score >= 0.4  -> if (track.artist.isNotBlank()) "You listen to ${track.artist}" else "Similar to artists you like"
            score >= 0.1  -> "Similar to artists you like"
            else          -> "Explore something new"
        }
    }
}

internal fun String.normArtist(): String = lowercase().trim()
