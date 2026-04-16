package org.kvxd.vinlien.server

import org.kvxd.vinlien.shared.models.feed.RecResult
import org.kvxd.vinlien.shared.models.media.Track

class RecommendationEngine {

    companion object {
        const val SKIP_THRESHOLD_MS = 30_000L

        const val FAMILIAR_ARTIST_TOP_N = 10
    }

    data class HistoryEntry(val trackId: String, val artist: String, val timestampMs: Long)
    data class SkipEntry(val trackId: String, val artist: String, val playedMs: Long)

    data class ListeningVector(
        val artistWeights: Map<String, Double>,
        val artistSkipCounts: Map<String, Int>,
        val skippedTrackIds: Set<String>,
        val topArtists: List<String>
    )

    fun buildListeningVector(
        history: List<HistoryEntry>,
        skips: List<SkipEntry>,
        @Suppress("UNUSED_PARAMETER") decayDays: Int = 7
    ): ListeningVector {
        val artistCounts = mutableMapOf<String, Double>()
        for (entry in history) {
            val artist = entry.artist.normalizedArtist()
            if (artist.isNotBlank()) artistCounts[artist] = (artistCounts[artist] ?: 0.0) + 1.0
        }

        val maxCount = artistCounts.values.maxOrNull() ?: 1.0
        val artistWeights = artistCounts.mapValues { (_, v) -> v / maxCount }

        val earlySkips = skips.filter { it.playedMs < SKIP_THRESHOLD_MS }
        val artistSkipCounts = earlySkips.groupingBy { it.artist.normalizedArtist() }.eachCount()
        val skippedTrackIds = earlySkips.map { it.trackId }.toSet()

        val topArtists = artistWeights.entries
            .sortedByDescending { it.value }
            .take(FAMILIAR_ARTIST_TOP_N)
            .map { it.key }

        return ListeningVector(artistWeights, artistSkipCounts, skippedTrackIds, topArtists)
    }

    fun scoreCandidate(
        track: Track,
        vector: ListeningVector,
        recentPlayedIds: List<String>,
        decayDays: Int = 7
    ): Double {
        if (track.id in vector.skippedTrackIds) return Double.NEGATIVE_INFINITY

        val artist = track.artist.normalizedArtist()
        val familiarityBonus = (vector.artistWeights[artist] ?: 0.0) * 60.0
        val skipPenalty = (vector.artistSkipCounts[artist] ?: 0) * 40.0

        val historyIdx = recentPlayedIds.indexOfFirst { id ->
            id == track.id || (track.canonicalId != null && id == track.canonicalId)
        }
        val recencyPenalty = if (historyIdx < 0) {
            0.0
        } else {
            val windowSize = (decayDays * 5).coerceAtLeast(1)
            val relativeRecency = 1.0 - (historyIdx.toDouble() / windowSize)
            relativeRecency.coerceIn(0.0, 1.0) * 80.0
        }

        return familiarityBonus - skipPenalty - recencyPenalty
    }

    fun buildReason(track: Track, vector: ListeningVector, seeds: List<Track>): String {
        val artist = track.artist.normalizedArtist()
        val weight = vector.artistWeights[artist] ?: 0.0
        val bestSeed = seeds.maxByOrNull { vector.artistWeights[it.artist.normalizedArtist()] ?: 0.0 }
        return when {
            weight >= 0.7 -> "Because you like ${track.artist}"
            weight >= 0.4 -> "You listen to ${track.artist}"
            bestSeed != null && (vector.artistWeights[bestSeed.artist.normalizedArtist()] ?: 0.0) >= 0.2
                -> "Similar to ${bestSeed.artist}"
            else -> "Discover: ${track.artist}"
        }
    }

    fun pickWithDiversity(
        candidates: List<Track>,
        vector: ListeningVector,
        seeds: List<Track>,
        recentPlayedIds: List<String>,
        sessionArtists: List<String>,
        decayDays: Int = 7,
        noveltyBudget: Float = 0.30f,
        maxConsecutiveSameArtist: Int = 3
    ): Pair<Track, String>? {
        if (candidates.isEmpty()) return null

        val scored = candidates.map { t -> t to scoreCandidate(t, vector, recentPlayedIds, decayDays) }
        val valid = scored.filter { (_, s) -> s.isFinite() }
        if (valid.isEmpty()) return null

        val lastN = sessionArtists.takeLast(maxConsecutiveSameArtist).map { it.normalizedArtist() }
        val blockedByConsecutive = run {
            val lastArtist = lastN.lastOrNull() ?: return@run null
            if (lastN.size >= maxConsecutiveSameArtist && lastN.all { it == lastArtist }) lastArtist
            else null
        }

        val needsNovelty = sessionArtists.isNotEmpty() && run {
            val familiarCount = sessionArtists.count { it.normalizedArtist() in vector.topArtists }
            familiarCount.toFloat() / sessionArtists.size > (1f - noveltyBudget)
        }

        fun List<Pair<Track, Double>>.best() = maxByOrNull { it.second }?.first

        val pick = when {
            needsNovelty -> {
                val unfamiliar = valid.filter { (t, _) -> t.artist.normalizedArtist() !in vector.topArtists }
                unfamiliar.best() ?: valid.best()
            }

            blockedByConsecutive != null -> {
                val different = valid.filter { (t, _) -> t.artist.normalizedArtist() != blockedByConsecutive }
                different.best() ?: valid.best()
            }

            else -> valid.best()
        } ?: return null

        return pick to buildReason(pick, vector, seeds)
    }

    fun buildRadioQueue(
        candidates: List<Track>,
        vector: ListeningVector,
        seeds: List<Track>,
        recentPlayedIds: List<String>,
        sessionArtists: List<String>,
        queueSize: Int = 10,
        decayDays: Int = 7,
        noveltyBudget: Float = 0.30f,
        maxConsecutiveSameArtist: Int = 3
    ): List<RecResult> {
        val result = mutableListOf<RecResult>()
        val remaining = candidates.toMutableList()
        val currentSession = sessionArtists.toMutableList()

        while (result.size < queueSize && remaining.isNotEmpty()) {
            val pick = pickWithDiversity(
                remaining, vector, seeds, recentPlayedIds, currentSession,
                decayDays, noveltyBudget, maxConsecutiveSameArtist
            ) ?: break
            result.add(RecResult(pick.first, pick.second))
            currentSession.add(pick.first.artist.normalizedArtist())
            remaining.removeAll { it.id == pick.first.id }
        }

        return result
    }

    fun shouldReseed(tracksPlayedInSession: Int, reseedInterval: Int = 5): Boolean =
        tracksPlayedInSession > 0 && tracksPlayedInSession % reseedInterval == 0

    fun pickReseedTrack(
        candidates: List<Track>,
        currentSeed: Track,
        vector: ListeningVector
    ): Track? {
        if (candidates.isEmpty()) return null
        val currentArtist = currentSeed.artist.normalizedArtist()
        return candidates
            .filter { it.artist.normalizedArtist() != currentArtist && it.id !in vector.skippedTrackIds }
            .maxByOrNull { vector.artistWeights[it.artist.normalizedArtist()] ?: 0.0 }
            ?: candidates.firstOrNull { it.id !in vector.skippedTrackIds }
    }

    private fun String.normalizedArtist(): String = lowercase().trim()
}
