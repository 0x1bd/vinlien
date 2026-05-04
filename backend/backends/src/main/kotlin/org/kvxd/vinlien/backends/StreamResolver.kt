package org.kvxd.vinlien.backends

import kotlinx.coroutines.*
import org.kvxd.vinlien.shared.models.media.Track

internal val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

private val VERSION_TERMS = Regex(
    """\b(acoustic|live|instrumental|karaoke|cover|demo|unplugged|remixed?|extended|radio\s+edit|remastered?|reissue)\b"""
)

private val NON_MUSIC_TERMS = Regex(
    """\b(bewertet|review|reaction|reacts|live aus|konzert|full album|playlist|medley|compilation|best of|mix|dj set|podcast|interview|making of|behind the scenes|tutorial|how to)\b""",
    RegexOption.IGNORE_CASE
)

private val OFFICIAL_VIDEO_TERMS = Regex(
    """\b(official\s*(video|audio|music\s*video|lyric\s*video|visualizer))\b""",
    RegexOption.IGNORE_CASE
)

private const val MIN_SONG_DURATION_MS = 60_000L
private const val MAX_SONG_DURATION_MS = 480_000L

data class StreamCandidate(val track: Track, val provider: MusicProvider, val score: Int)

class StreamResolver(private val providers: List<MusicProvider>) {

    suspend fun resolve(track: Track, preferredProviderId: String? = null): String =
        Profiler.measure("StreamResolver.resolve(${track.artist} - ${track.title})") {
            findNativeProvider(track)?.resolveStream(track)?.let { return@measure it }

            val rankedCandidates = searchAndRankCandidates(track, preferredProviderId)
            for (candidate in rankedCandidates) {
                val url = runCatching { candidate.provider.resolveStream(candidate.track) }.getOrNull()
                if (url != null) return@measure url
            }

            throw Exception("No stream available for: ${track.artist} - ${track.title}")
        }

    private fun findNativeProvider(track: Track): MusicProvider? {
        val nativeProviderId = track.id.substringBefore(":")
        val nativeProvider = providers.find { it.id == nativeProviderId && Capability.AUDIO_STREAM in it.capabilities }
        if (nativeProvider != null) return nativeProvider

        val isRawYoutubeId = track.id.matches(YT_ID_REGEX)
        return if (isRawYoutubeId) providers.find { it.id == "invidious" && Capability.AUDIO_STREAM in it.capabilities }
        else null
    }

    private suspend fun searchAndRankCandidates(
        track: Track,
        preferredProviderId: String?
    ): List<StreamCandidate> {
        val searchQuery = buildSearchQuery(track)
        val audioProviders = providers.filter { Capability.AUDIO_STREAM in it.capabilities }

        val candidates = coroutineScope {
            audioProviders.map { provider ->
                async {
                    runCatching { provider.searchAudio(searchQuery) }.getOrElse { emptyList() }
                        .map { candidate -> StreamCandidate(candidate, provider, scoreMatch(candidate, track)) }
                }
            }.awaitAll().flatten()
        }

        return candidates
            .filter { it.score > 0 }
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

        val titleScore = when {
            candidateTitle == targetTitle -> 20
            candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle) -> 10
            candidateTitle.withoutSpaces().contains(targetTitle.withoutSpaces()) ||
                    targetTitle.withoutSpaces().contains(candidateTitle.withoutSpaces()) -> 5
            else -> return 0
        }

        val targetArtists = target.artists
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

        val officialVideoBonus = if (OFFICIAL_VIDEO_TERMS.containsMatchIn(candidate.title)) 15 else 0

        val durationMs = candidate.durationMs
        val durationBonus = if (durationMs > 0) {
            when {
                durationMs in MIN_SONG_DURATION_MS..MAX_SONG_DURATION_MS -> 10
                durationMs < MIN_SONG_DURATION_MS -> -10
                else -> -5
            }
        } else 0

        return titleScore + artistScore + (if (artistNameAppearsInTitle) 5 else 0) + officialVideoBonus + durationBonus
    }
}
