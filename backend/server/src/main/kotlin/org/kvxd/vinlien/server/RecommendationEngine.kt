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
        val likedTrackIds: Set<String>,
        val dislikedTrackIds: Set<String>,
        val dislikedArtistIds: Set<String>,
        val trackSkipScores: Map<String, Double>,
        val artistSkipScores: Map<String, Double>,
        val topFamiliarArtists: Set<String>,
        val tasteCapsules: List<TasteCapsuleModel>,
        val globalFeatureCentroid: Map<String, Double>
    )

    fun buildListeningVector(
        history: List<HistoryEntry>,
        skips: List<SkipEntry>,
        likedArtists: Set<String> = emptySet(),
        likedTrackIds: Set<String> = emptySet(),
        dislikedTrackIds: Set<String> = emptySet(),
        dislikedArtists: Set<String> = emptySet(),
        tasteSignals: List<TasteTrackSignal> = emptyList(),
        tasteCapsules: List<TasteCapsuleModel> = emptyList(),
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

        val globalFeatureCentroid = TasteGraph.centroid(
            tasteSignals
                .sortedByDescending { it.weight }
                .take(80)
                .map { signal ->
                    TasteGraph.extractFeatures(signal.track).mapValues { it.value * signal.weight }
                }
        )

        return ListeningVector(
            artistScores = artistScores,
            likedArtistIds = likedArtists,
            likedTrackIds = likedTrackIds,
            dislikedTrackIds = dislikedTrackIds,
            dislikedArtistIds = dislikedArtists,
            trackSkipScores = trackSkipScores,
            artistSkipScores = artistSkipScores,
            topFamiliarArtists = topFamiliarArtists,
            tasteCapsules = tasteCapsules,
            globalFeatureCentroid = globalFeatureCentroid
        )
    }

    fun scoreCandidate(
        track: Track,
        vector: ListeningVector,
        recentPlayedIds: Set<String>,
        contextFeatures: Map<String, Double> = emptyMap(),
        activeCapsules: List<Pair<TasteCapsuleModel, Double>> = emptyList()
    ): Double {
        val artist = track.artist.normArtist()
        val artistScore = vector.artistScores[artist] ?: 0.0
        val trackIds = listOfNotNull(track.id, track.canonicalId).toSet()

        val capsuleFit = TasteGraph.capsuleFit(track, activeCapsules)
        val globalFit = TasteGraph.featureSimilarity(track, vector.globalFeatureCentroid)
        val contextFit = TasteGraph.featureSimilarity(track, contextFeatures)

        val familiarityBonus = artistScore * 18.0
        val capsuleBonus = capsuleFit * 88.0
        val globalTasteBonus = globalFit * 30.0
        val transitionBonus = contextFit * 22.0
        val likedArtistBonus = if (artist in vector.likedArtistIds) 16.0 * (1.0 - artistScore * 0.35) else 0.0
        val likedTrackBonus = if (trackIds.any { it in vector.likedTrackIds }) 42.0 else 0.0
        val artworkUrl = track.artworkUrl
        val qualityBonus = (if (artworkUrl != null && !artworkUrl.contains("ytimg.com")) 4.0 else 0.0) +
                (if (track.lastFmUrl != null) 3.0 else 0.0) +
                (if (track.durationMs > 0) 2.0 else 0.0)

        val trackSkipPenalty = trackIds.maxOfOrNull { vector.trackSkipScores[it] ?: 0.0 }?.times(24.0) ?: 0.0
        val explicitDislikePenalty = if (trackIds.any { it in vector.dislikedTrackIds } || artist in vector.dislikedArtistIds) 260.0 else 0.0
        val recentPenalty = if (trackIds.any { it in recentPlayedIds }) 70.0 else 0.0

        return familiarityBonus +
                capsuleBonus +
                globalTasteBonus +
                transitionBonus +
                likedArtistBonus +
                likedTrackBonus +
                qualityBonus -
                trackSkipPenalty -
                explicitDislikePenalty -
                recentPenalty
    }

    private fun sampleSoftmax(
        candidates: List<Pair<Track, Double>>,
        temperature: Float,
        blockedArtist: String? = null,
        forceNoveltyFrom: Set<String>? = null,
        preferArtistsOutside: Set<String> = emptySet()
    ): Track? {
        var pool = candidates.filter { (_, s) -> s.isFinite() }
        if (pool.isEmpty()) return null

        if (preferArtistsOutside.isNotEmpty()) {
            val freshArtists = pool.filter { (t, _) -> t.artist.normArtist() !in preferArtistsOutside }
            if (freshArtists.isNotEmpty()) pool = freshArtists
        }
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
        maxConsecutiveSameArtist: Int = 3,
        contextTracks: List<Track> = emptyList(),
        selectedTracks: List<Track> = emptyList()
    ): Pair<Track, String>? {
        if (candidates.isEmpty()) return null

        val activeCapsules = TasteGraph.activeCapsules(vector.tasteCapsules, contextTracks, limit = 3)
        val contextFeatures = TasteGraph.centroid(contextTracks.takeLast(12).map(TasteGraph::extractFeatures))
        val selectedArtists = selectedTracks.map { it.artist.normArtist() }.toSet()
        val recentSessionArtistList = sessionArtists.takeLast(8).map { it.normArtist() }
        val recentSessionArtists = recentSessionArtistList.toSet()
        val scored = candidates.map { t ->
            val artist = t.artist.normArtist()
            val artistScore = vector.artistScores[artist] ?: 0.0
            val capsuleFit = TasteGraph.capsuleFit(t, activeCapsules)
            val globalFit = TasteGraph.featureSimilarity(t, vector.globalFeatureCentroid)
            val redundancy = selectedTracks.maxOfOrNull { selected -> TasteGraph.featureSimilarity(t, selected) } ?: 0.0
            val sameArtistSelected = artist in selectedArtists
            val freshArtist = artistScore < 0.08
            val noveltyLift = when {
                freshArtist && capsuleFit >= 0.16 -> noveltyBudget * (16.0 + globalFit * 14.0)
                freshArtist && globalFit >= 0.10 -> noveltyBudget * 8.0
                artistScore < 0.22 -> noveltyBudget * 5.0
                else -> 0.0
            }
            val familiarArtistPenalty = if (artist in vector.topFamiliarArtists) {
                noveltyBudget * (5.0 + artistScore * 12.0)
            } else {
                0.0
            }
            val sessionRepeatPenalty = recentSessionArtistList.count { it == artist } * (6.0 + noveltyBudget * 10.0)

            val score = scoreCandidate(t, vector, recentPlayedIds, contextFeatures, activeCapsules) +
                    noveltyLift -
                    familiarArtistPenalty -
                    sessionRepeatPenalty -
                    redundancy * (34.0 + noveltyBudget * 38.0) -
                    if (sameArtistSelected) 28.0 + noveltyBudget * 24.0 else 0.0
            t to score
        }

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
        val preferOutsideArtists = selectedArtists + recentSessionArtists

        val pick = sampleSoftmax(
            scored,
            temperature,
            blockedArtist,
            if (forceNovelty) vector.topFamiliarArtists else null,
            preferOutsideArtists
        ) ?: return null

        return pick to buildReason(pick, vector, contextTracks)
    }

    fun buildRadioQueue(
        candidates: List<Track>,
        vector: ListeningVector,
        recentPlayedIds: Set<String>,
        sessionArtists: List<String>,
        queueSize: Int = 10,
        noveltyBudget: Float = 0.30f,
        maxConsecutiveSameArtist: Int = 3,
        contextTracks: List<Track> = emptyList()
    ): List<RecResult> {
        val result = mutableListOf<RecResult>()
        val remaining = candidates.toMutableList()
        val currentSession = sessionArtists.toMutableList()
        val selectedTracks = mutableListOf<Track>()

        while (result.size < queueSize && remaining.isNotEmpty()) {
            val pick = pickWithDiversity(
                candidates = remaining,
                vector = vector,
                recentPlayedIds = recentPlayedIds,
                sessionArtists = currentSession,
                noveltyBudget = noveltyBudget,
                maxConsecutiveSameArtist = maxConsecutiveSameArtist,
                contextTracks = contextTracks + selectedTracks,
                selectedTracks = selectedTracks
            ) ?: break
            result.add(RecResult(pick.first, pick.second))
            selectedTracks.add(pick.first)
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

    fun buildReason(track: Track, vector: ListeningVector, contextTracks: List<Track> = emptyList()): String {
        val artist = track.artist.normArtist()
        val score = vector.artistScores[artist] ?: 0.0
        val activeCapsules = TasteGraph.activeCapsules(vector.tasteCapsules, contextTracks, limit = 3)
        val bestCapsule = activeCapsules
            .map { it.first to TasteGraph.capsuleFit(track, listOf(it)) }
            .maxByOrNull { it.second }
        return when {
            bestCapsule != null && bestCapsule.second >= 0.28 -> "Fits ${bestCapsule.first.label}"
            artist in vector.likedArtistIds && score >= 0.4 -> "From your favourites"
            score >= 0.75 -> "One of your top artists"
            score >= 0.4  -> if (track.artist.isNotBlank()) "You listen to ${track.artist}" else "Similar to artists you like"
            score >= 0.1  -> "Similar to artists you like"
            else          -> "Explore something new"
        }
    }
}

internal fun String.normArtist(): String = lowercase().trim()
