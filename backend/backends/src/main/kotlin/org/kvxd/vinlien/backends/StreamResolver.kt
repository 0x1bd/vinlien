package org.kvxd.vinlien.backends

import kotlinx.coroutines.*
import org.kvxd.vinlien.shared.models.media.Track
import org.slf4j.LoggerFactory
import kotlin.math.abs

internal val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

private val VERSION_TERMS = Regex(
    """\b(acoustic|live|instrumental|karaoke|cover|demo|unplugged|remixed?|extended|radio\s+edit|remastered?|reissue|slowed|reverb|sped\s+up|speed\s+up|nightcore|8d|loop|remake)\b""",
    RegexOption.IGNORE_CASE
)

private val NON_MUSIC_TERMS = Regex(
    """\b(bewertet|review|reaction|reacts|live aus|konzert|full album|playlist|medley|compilation|best of|mix|dj set|podcast|interview|making of|behind the scenes|tutorial|how to)\b""",
    RegexOption.IGNORE_CASE
)

private val OFFICIAL_VIDEO_TERMS = Regex(
    """\b(official\s*(video|audio|music\s*video|lyric\s*video|visualizer))\b""",
    RegexOption.IGNORE_CASE
)

private val VISUALIZER_TERMS = Regex(
    """\b(official\s+visuali[sz]er|visuali[sz]er)\b""",
    RegexOption.IGNORE_CASE
)

private const val MIN_SONG_DURATION_MS = 60_000L
private const val MAX_SONG_DURATION_MS = 480_000L

private val ARTIST_NAME_SPLIT = Regex(
    """[\s]*[&,][\s]*|[\s]+(?:x|and)\s+|[\s]+(?<![a-zA-Z])(?:feat|ft|featuring)\.?\s+""",
    RegexOption.IGNORE_CASE
)

private val PARENTHETICAL_WORDS = Regex(
    """\b(official|video|audio|music|lyric|lyrics|visualizer|hd|hq|4k|clip|vevo|zugabe|bonus|remix|edit|version|mix)\b""",
    RegexOption.IGNORE_CASE
)

private fun stripParentheticalWords(title: String): String =
    PARENTHETICAL_WORDS.replace(title, "").replace(Regex("\\s+"), " ").trim()

data class StreamCandidate(val track: Track, val provider: MusicProvider, val score: Int)

class StreamResolver(private val providers: List<MusicProvider>) {
    private val logger = LoggerFactory.getLogger(StreamResolver::class.java)

    suspend fun resolve(track: Track, preferredProviderId: String? = null): String =
        resolveWithProvider(track, preferredProviderId).streamUrl

    suspend fun resolveWithProvider(track: Track, preferredProviderId: String? = null): StreamResolutionResult.Success =
        Profiler.measure("StreamResolver.resolve(${track.artist} - ${track.title})") {
            val rankedCandidates = searchAndRankCandidates(track, preferredProviderId)
            logger.info("Stream resolution for '{} - {}': {} ranked candidates",
                track.artist, track.title, rankedCandidates.size)

            if (rankedCandidates.isNotEmpty()) {
                rankedCandidates.forEachIndexed { i, c ->
                    logger.info("  Candidate #{}: [{}] '{} - {}' (id={}, score={})",
                        i + 1, c.provider.id, c.track.artist, c.track.title, c.track.id, c.score)
                }
            }

            val failures = mutableListOf<StreamResolutionResult.Failure>()
            for (candidate in rankedCandidates) {
                logger.info("Trying provider '{}' for candidate '{} - {}' (score={})",
                    candidate.provider.id, candidate.track.artist, candidate.track.title, candidate.score)

                val result = candidate.provider.resolveStream(candidate.track)
                when (result) {
                    is StreamResolutionResult.Success -> {
                        logger.info("Provider '{}' resolved stream successfully", candidate.provider.id)
                        return@measure result
                    }
                    is StreamResolutionResult.Failure -> {
                        logger.warn("Provider '{}' failed for '{} - {}': {}",
                            candidate.provider.id, candidate.track.artist, candidate.track.title, result.reason)
                        failures.add(result)
                    }
                }
            }

            val errorDetail = buildDetailedError(track, rankedCandidates, failures)
            throw Exception(errorDetail)
        }

    private fun buildDetailedError(
        track: Track,
        candidates: List<StreamCandidate>,
        failures: List<StreamResolutionResult.Failure>
    ): String {
        val sb = StringBuilder()
        sb.append("No stream available for: ${track.artist} - ${track.title}")
        sb.append(" (${candidates.size} candidates, 0 successful)")

        if (failures.isNotEmpty()) {
            sb.append("\nProvider failures:")
            failures.forEach { f ->
                sb.append("\n  [${f.providerId}] ${f.reason}")
                if (f.cause != null && logger.isDebugEnabled) {
                    sb.append(" (${f.cause::class.simpleName})")
                }
            }
        }

        if (candidates.isEmpty()) {
            sb.append("\nNo candidates passed scoring -- check search results and match criteria")
        }

        return sb.toString()
    }

    private fun findNativeProvider(track: Track): MusicProvider? {
        val nativeProviderId = track.id.substringBefore(":")
        val nativeProvider = providers.find { it.id == nativeProviderId && Capability.AUDIO_STREAM in it.capabilities }
        if (nativeProvider != null) return nativeProvider

        val isRawYoutubeId = track.id.matches(YT_ID_REGEX)
        return if (isRawYoutubeId) providers.find { it.id == "ytmusic" && Capability.AUDIO_STREAM in it.capabilities }
        else null
    }

    private suspend fun searchAndRankCandidates(
        track: Track,
        preferredProviderId: String?
    ): List<StreamCandidate> {
        val searchQuery = buildSearchQuery(track)
        val audioProviders = providers.filter { Capability.AUDIO_STREAM in it.capabilities }
        logger.info("Searching {} audio providers with query: '{}'", audioProviders.size, searchQuery)

        val nativeCandidate = findNativeProvider(track)?.let { nativeProvider ->
            StreamCandidate(track, nativeProvider, scoreMatch(track, track))
        }

        val candidates = coroutineScope {
            audioProviders.map { provider ->
                async {
                    val results = runCatching { provider.searchAudio(searchQuery) }.getOrElse { e ->
                        logger.warn("Provider '{}' search failed for query '{}': {}", provider.id, searchQuery, e.message)
                        emptyList()
                    }
                    logger.info("Provider '{}' returned {} results for '{}'", provider.id, results.size, searchQuery)
                    results.map { candidate -> StreamCandidate(candidate, provider, scoreMatch(candidate, track)) }
                }
            }.awaitAll().flatten()
        } + listOfNotNull(nativeCandidate)

        val scored = candidates.filter { it.score > 0 }
        val zeroScore = candidates.filter { it.score == 0 }

        if (zeroScore.isNotEmpty()) {
            logger.debug("Filtered out {} zero-score candidates, top examples:\n  {}",
                zeroScore.size, zeroScore.take(5).joinToString("\n  ") {
                    "[${it.provider.id}] '${it.track.artist} - ${it.track.title}' (id=${it.track.id})"
                })
        }

        logger.info("After scoring: {} candidates passed ({} filtered out)", scored.size, zeroScore.size)

        return scored
            .sortedWith(
                compareByDescending<StreamCandidate> { it.score }
                    .thenByDescending { if (it.provider.id.equals(preferredProviderId, ignoreCase = true)) 1 else 0 }
            )
    }

    private fun buildSearchQuery(track: Track): String {
        val artistWithoutFeatures = track.artist.withoutFeaturedArtists()
        val titleWithoutVersionSuffix = track.title.replace(Regex("""\s*[\[(][^\]\[)]*[\])]"""), "").trim()
        return "$artistWithoutFeatures $titleWithoutVersionSuffix"
    }

    private fun scoreMatch(candidate: Track, target: Track): Int {
        val candidateTitle = candidate.title.normalized()
        val targetTitle = target.title.normalized()
        if (candidateTitle.isEmpty() || targetTitle.isEmpty()) return 0

        if (NON_MUSIC_TERMS.containsMatchIn(candidate.title)) return 0

        val targetHasVersionSuffix = VERSION_TERMS.containsMatchIn(targetTitle)
        val candidateHasVersionSuffix = VERSION_TERMS.containsMatchIn(candidateTitle)
        if (!targetHasVersionSuffix && candidateHasVersionSuffix) return 0

        val candidateBase = stripParentheticalWords(candidateTitle)
        val targetBase = stripParentheticalWords(targetTitle)

        val titleScore = when {
            candidateTitle == targetTitle -> 20
            candidateBase == targetBase -> 18
            candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle) -> 10
            candidateBase.contains(targetBase) || targetBase.contains(candidateBase) -> 8
            candidateTitle.withoutSpaces().contains(targetTitle.withoutSpaces()) ||
                    targetTitle.withoutSpaces().contains(candidateTitle.withoutSpaces()) -> 5
            candidateBase.withoutSpaces().contains(targetBase.withoutSpaces()) ||
                    targetBase.withoutSpaces().contains(candidateBase.withoutSpaces()) -> 3
            else -> return 0
        }

        val targetArtists = target.artists.ifEmpty { target.artist.split(ARTIST_NAME_SPLIT) }
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(normalizeArtistName(target.artist)) }
        val candidateArtist = normalizeArtistName(candidate.artist)

        val artistScore = targetArtists.maxOf { targetArtist ->
            when {
                candidateArtist == targetArtist -> 20
                candidateArtist.contains(targetArtist) || targetArtist.contains(candidateArtist) -> 15
                candidateArtist.withoutSpaces().contains(targetArtist.withoutSpaces()) ||
                        targetArtist.withoutSpaces().contains(candidateArtist.withoutSpaces()) -> 10
                else -> 0
            }
        }

        val artistNameAppearsInTitle = targetArtists.any { targetArtist ->
            candidateTitle.withoutSpaces().contains(targetArtist.withoutSpaces())
        }

        val isVisualizer = VISUALIZER_TERMS.containsMatchIn(candidate.title)
        val officialVideoBonus = if (!isVisualizer && OFFICIAL_VIDEO_TERMS.containsMatchIn(candidate.title)) 15 else 0
        val visualizerPenalty = if (isVisualizer) -45 else 0

        val durationMs = candidate.durationMs
        val durationBonus = if (durationMs > 0) {
            when {
                durationMs in MIN_SONG_DURATION_MS..MAX_SONG_DURATION_MS -> 10
                durationMs < MIN_SONG_DURATION_MS -> -10
                else -> -5
            }
        } else 0

        val durationMismatchPenalty = when {
            target.durationMs <= 0 || candidate.durationMs <= 0 -> 0
            candidate.durationMs > (target.durationMs * 1.35).toLong() &&
                    abs(candidate.durationMs - target.durationMs) > 30_000L -> return 0
            abs(candidate.durationMs - target.durationMs) <= 10_000L -> 10
            abs(candidate.durationMs - target.durationMs) > 30_000L -> -10
            else -> 0
        }

        return titleScore + artistScore + (if (artistNameAppearsInTitle) 5 else 0) +
                officialVideoBonus + visualizerPenalty + durationBonus + durationMismatchPenalty
    }
}
