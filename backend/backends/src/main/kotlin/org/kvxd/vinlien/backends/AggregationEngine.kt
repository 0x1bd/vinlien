package org.kvxd.vinlien.backends

import kotlinx.coroutines.*
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

class AggregationEngine(val providers: List<MusicProvider>) {

    private suspend fun <T : Any> parallelQuery(
        capability: Capability,
        block: suspend (MusicProvider) -> T?
    ): List<T> = coroutineScope {
        providers
            .filter { capability in it.capabilities }
            .map { p ->
                async {
                    withTimeoutOrNull(p.timeoutMs) {
                        runCatching { block(p) }.getOrNull()
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .filterIsInstance<Any>()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as T
            }
    }

    suspend fun searchTracks(query: String): List<Track> {
        val raw = parallelQuery<List<Track>>(Capability.TRACK_SEARCH) { it.searchTracks(query) }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.track(it) })
    }

    suspend fun searchAlbums(query: String): List<Album> {
        val raw = parallelQuery<List<Album>>(Capability.ALBUM_SEARCH) { it.searchAlbums(query) }.flatten()
        return AlbumMerger.dedup(raw.map { Normalizer.album(it) })
    }

    suspend fun getAlbum(nativeId: String): Album? {
        val (artist, title) = parseAlbumArtistTitle(nativeId) ?: return null
        val results = parallelQuery<Album>(Capability.ALBUM_TRACKS) { it.getAlbum(artist, title) }
        return AlbumMerger.mergeOne(results, nativeId)?.let { Normalizer.album(it) }
    }

    suspend fun getArtistAlbums(artist: String): List<Album> {
        val raw = parallelQuery<List<Album>>(Capability.ARTIST_ALBUMS) { it.getArtistAlbums(artist) }.flatten()
        return AlbumMerger.dedup(raw.map { Normalizer.album(it) })
    }

    suspend fun getArtistInfo(name: String): ArtistInfo? {
        val results = parallelQuery<ArtistInfo>(Capability.ARTIST_INFO) { it.getArtistInfo(name) }
        return ArtistInfoMerger.mergeOne(results)
    }

    suspend fun getRecommendations(track: Track): List<Track> {
        val raw = parallelQuery<List<Track>>(Capability.RECOMMENDATIONS) { it.getRecommendations(track) }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.track(it) })
    }

    suspend fun getTrending(): List<Track> {
        val raw = parallelQuery<List<Track>>(Capability.TRENDING) {
            it.getTrending().also {
            }
        }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.track(it) })
    }

    suspend fun resolveStream(track: Track, preferredProviderId: String? = null): String {
        val nativeProviderId = track.id.substringBefore(":")
        val native = providers.find { it.id == nativeProviderId && Capability.AUDIO_STREAM in it.capabilities }
        native?.resolveStream(track)?.let { return it }

        val cleanArtist = track.artist
            .replace(Regex("""\s+(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "")
            .trim()
        val searchQuery = "$cleanArtist ${track.title}"

        val audioProviders = providers.filter { Capability.AUDIO_STREAM in it.capabilities && it != native }

        data class Candidate(val track: Track, val provider: MusicProvider, val score: Int)

        val scored = coroutineScope {
            audioProviders.map { p ->
                async {
                    runCatching { p.searchAudio(searchQuery) }.getOrElse { emptyList() }
                        .map { c -> Candidate(c, p, streamMatchScore(c, track)) }
                }
            }.awaitAll().flatten()
        }

        val sorted = scored
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<Candidate> { it.score }
                .thenByDescending { if (it.provider.id.equals(preferredProviderId, ignoreCase = true)) 1 else 0 })

        for (c in sorted) {
            val url = runCatching { c.provider.resolveStream(c.track) }.getOrNull()
            if (url != null) return url
        }

        throw Exception("No stream available for: ${track.artist} - ${track.title}")
    }

    private fun streamMatchScore(candidate: Track, target: Track): Int {
        fun String.norm() = lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
        fun String.noSpace() = replace(" ", "")

        val cTitle = candidate.title.norm()
        val tTitle = target.title.norm()
        if (cTitle.isEmpty() || tTitle.isEmpty()) return 0

        val versionWords = setOf(
            "acoustic",
            "live",
            "instrumental",
            "karaoke",
            "cover",
            "demo",
            "unplugged",
            "remix",
            "extended",
            "radio edit",
            "remaster"
        )
        val targHasVersion = versionWords.any { it in tTitle.split(" ") }
        if (!targHasVersion && versionWords.any { it in cTitle.split(" ") }) return 0   // wrong version

        val titleScore = when {
            cTitle == tTitle -> 20
            cTitle.contains(tTitle) || tTitle.contains(cTitle) -> 10
            cTitle.noSpace().contains(tTitle.noSpace()) || tTitle.noSpace().contains(cTitle.noSpace()) -> 5
            else -> return 0
        }

        fun normArtist(s: String) = s.norm()
            .replace(Regex("""\s*(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "").trim()

        val targetArtists = target.artists.map { normArtist(it) }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(normArtist(target.artist)) }
        val candArtist = normArtist(candidate.artist)

        val artistScore = targetArtists.maxOf { tArtist ->
            when {
                candArtist == tArtist -> 20
                candArtist.contains(tArtist) || tArtist.contains(candArtist) -> 15
                candArtist.noSpace().contains(tArtist.noSpace()) || tArtist.noSpace()
                    .contains(candArtist.noSpace()) -> 10

                else -> 0
            }
        }

        val artistInTitle = targetArtists.any { tArtist ->
            cTitle.noSpace().contains(tArtist.noSpace())
        }

        return titleScore + artistScore + (if (artistInTitle) 5 else 0)
    }

    fun parseAlbumArtistTitle(nativeId: String): Pair<String, String>? = when {
        nativeId.startsWith("lastfm:album:") -> {
            val rest = nativeId.removePrefix("lastfm:album:")
            val parts = rest.split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        nativeId.startsWith("itunes:album:") -> {
            val rest = nativeId.removePrefix("itunes:album:")
            val parts = rest.split(":::", limit = 3)
            if (parts.size == 3) parts[1] to parts[2] else null
        }

        nativeId.startsWith("mb:album:") -> {
            val rest = nativeId.removePrefix("mb:album:")
            val parts = rest.split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        else -> null
    }

    private fun fuzzyMatch(candidate: Track, target: Track): Boolean {
        fun String.norm() = lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
        fun String.noSpace() = replace(" ", "")

        val cTitle = candidate.title.norm()
        val tTitle = target.title.norm()
        if (cTitle.length < 4 || tTitle.length < 4) return false

        val versionWords = setOf("acoustic", "live", "instrumental", "karaoke", "cover", "demo", "unplugged", "remix")
        if (versionWords.any { it in cTitle.split(" ") } && versionWords.none { it in tTitle.split(" ") }) return false

        val titleMatch = cTitle == tTitle || cTitle.contains(tTitle) || tTitle.contains(cTitle)

        fun artistNorm(s: String) = s.norm()
            .replace(Regex("""\s+(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "").trim()

        val cArtist = artistNorm(candidate.artist)
        val tArtist = artistNorm(target.artist)

        val artistMatch = cArtist == tArtist || cArtist.contains(tArtist) || tArtist.contains(cArtist) ||
                cArtist.noSpace().contains(tArtist.noSpace()) || tArtist.noSpace().contains(cArtist.noSpace())

        return titleMatch && (artistMatch || cTitle.noSpace().contains(tArtist.noSpace()))
    }
}
