package org.kvxd.vinlien.backends

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.SearchResponse
import org.kvxd.vinlien.shared.Track

private val VERSION_WORDS = setOf(
    "acoustic", "live", "instrumental", "karaoke", "cover", "demo", "unplugged", "remix"
)

class AggregationEngine(private val providers: List<MusicProvider>) {

    private val streamResolver = StreamResolver(providers)

    private fun <T> providerFlow(
        capability: Capability,
        block: suspend (MusicProvider) -> List<T>
    ): Flow<List<T>> = channelFlow {
        providers.filter { capability in it.capabilities }.forEach { provider ->
            launch {
                val result = withTimeoutOrNull(provider.timeoutMs) {
                    Profiler.measure("${provider.name} ${capability.name}") {
                        runCatching { block(provider) }.getOrNull()
                    }
                } ?: return@launch
                if (result.isNotEmpty()) send(result)
            }
        }
    }

    fun searchStreaming(query: String): Flow<SearchResponse> {
        val trackUpdates: Flow<List<Track>> = providerFlow(Capability.TRACK_SEARCH) { it.searchTracks(query) }
            .runningFold(emptyList()) { acc, batch ->
                TrackMerger.merge(acc + batch.map { Normalizer.normalizeTrack(it) })
                    .filter { it.artworkUrl != null }
            }

        val albumUpdates: Flow<List<Album>> = providerFlow(Capability.ALBUM_SEARCH) { it.searchAlbums(query) }
            .runningFold(emptyList()) { acc, batch ->
                AlbumMerger.dedup(acc + batch.map { Normalizer.normalizeAlbum(it) })
                    .filter { it.artworkUrl != null }
            }

        return trackUpdates.combine(albumUpdates) { tracks, albums -> SearchResponse(tracks, albums) }
    }

    private object RecommendationSignalWeights {
        const val HIGH_QUALITY_ARTWORK = 30
        const val HAS_LAST_FM_METADATA = 25
        const val FROM_NON_YOUTUBE_PROVIDER = 20
        const val LOW_QUALITY_ARTWORK = 5
        const val HAS_DURATION = 5
    }

    private suspend fun <T : Any> parallelQuery(
        capability: Capability,
        block: suspend (MusicProvider) -> T?
    ): List<T> = coroutineScope {
        providers
            .filter { capability in it.capabilities }
            .map { provider ->
                async {
                    withTimeoutOrNull(provider.timeoutMs) {
                        Profiler.measure("${provider.name} ${capability.name}") {
                            runCatching { block(provider) }.getOrNull()
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as T
            }
    }

    suspend fun searchTracks(query: String): List<Track> {
        val raw = parallelQuery(Capability.TRACK_SEARCH) { it.searchTracks(query) }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.normalizeTrack(it) })
    }

    suspend fun searchAlbums(query: String): List<Album> {
        val raw = parallelQuery<List<Album>>(Capability.ALBUM_SEARCH) { it.searchAlbums(query) }.flatten()
        return AlbumMerger.dedup(raw.map { Normalizer.normalizeAlbum(it) })
    }

    suspend fun getAlbum(nativeId: String): Album? {
        val (artist, title) = parseAlbumArtistTitle(nativeId) ?: return null
        val results = parallelQuery<Album>(Capability.ALBUM_TRACKS) { it.getAlbum(artist, title) }
        return AlbumMerger.mergeOne(results, nativeId)?.let { Normalizer.normalizeAlbum(it) }
    }

    suspend fun getArtistAlbums(artist: String): List<Album> {
        val raw = parallelQuery<List<Album>>(Capability.ARTIST_ALBUMS) { it.getArtistAlbums(artist) }.flatten()
        return AlbumMerger.dedup(raw.map { Normalizer.normalizeAlbum(it) })
    }

    suspend fun getArtistInfo(name: String): ArtistInfo? {
        val results = parallelQuery<ArtistInfo>(Capability.ARTIST_INFO) { it.getArtistInfo(name) }
        return ArtistInfoMerger.mergeOne(results)
    }

    suspend fun getRecommendations(track: Track): List<Track> {
        val curatedProviderRecs = parallelQuery(Capability.RECOMMENDATIONS) { provider ->
            val providesAudioStreams = Capability.AUDIO_STREAM in provider.capabilities
            if (providesAudioStreams) null else provider.getRecommendations(track)
        }.flatten()

        val rawRecs = curatedProviderRecs.ifEmpty {
            parallelQuery(Capability.RECOMMENDATIONS) { provider ->
                val providesAudioStreams = Capability.AUDIO_STREAM in provider.capabilities
                if (providesAudioStreams) provider.getRecommendations(track) else null
            }.flatten()
        }

        val normalized = rawRecs.map { Normalizer.normalizeTrack(it) }

        val metaProviders = providers.filter { Capability.TRACK_SEARCH in it.capabilities }
        val enriched = if (metaProviders.isEmpty()) normalized else coroutineScope {
            normalized.map { t ->
                async {
                    if (t.id.matches(YT_ID_REGEX)) enrichWithMetadata(t, metaProviders) else t
                }
            }.awaitAll()
        }

        return TrackMerger.merge(enriched).sortedByDescending { scoreRecommendation(it) }
    }

    private fun scoreRecommendation(track: Track): Int {
        val hasHighQualityArtwork = track.artworkUrl != null && !track.artworkUrl!!.contains("ytimg.com")
        val hasLowQualityArtwork = track.artworkUrl != null && track.artworkUrl!!.contains("ytimg.com")
        val hasLastFmMetadata = track.lastFmUrl != null
        val isFromNonYoutubeProvider = !track.id.matches(YT_ID_REGEX)
        val hasDuration = track.durationMs > 0

        return (if (hasHighQualityArtwork) RecommendationSignalWeights.HIGH_QUALITY_ARTWORK else 0) +
                (if (hasLowQualityArtwork) RecommendationSignalWeights.LOW_QUALITY_ARTWORK else 0) +
                (if (hasLastFmMetadata) RecommendationSignalWeights.HAS_LAST_FM_METADATA else 0) +
                (if (isFromNonYoutubeProvider) RecommendationSignalWeights.FROM_NON_YOUTUBE_PROVIDER else 0) +
                (if (hasDuration) RecommendationSignalWeights.HAS_DURATION else 0)
    }

    private suspend fun enrichWithMetadata(track: Track, metaProviders: List<MusicProvider>): Track {
        val query = "${track.artist} ${track.title}"
        for (provider in metaProviders) {
            val results = withTimeoutOrNull(3_000L) {
                runCatching { provider.searchTracks(query) }.getOrNull()
            } ?: continue
            val match = results.firstOrNull { fuzzyMatch(it, track) } ?: continue
            return track.copy(
                title = match.title.takeIf { it.isNotBlank() } ?: track.title,
                artist = match.artist.takeIf { it.isNotBlank() } ?: track.artist,
                artists = match.artists.ifEmpty { track.artists },
                artworkUrl = match.artworkUrl ?: track.artworkUrl,
                lastFmUrl = match.lastFmUrl ?: track.lastFmUrl,
                canonicalId = match.canonicalId ?: track.canonicalId
            )
        }
        return track
    }

    suspend fun getTrending(): List<Track> {
        val raw = parallelQuery(Capability.TRENDING) { it.getTrending() }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.normalizeTrack(it) })
    }

    suspend fun resolveStream(track: Track, preferredProviderId: String? = null): String =
        streamResolver.resolve(track, preferredProviderId)

    fun parseAlbumArtistTitle(nativeId: String): Pair<String, String>? = when {
        nativeId.startsWith("lastfm:album:") -> {
            val parts = nativeId.removePrefix("lastfm:album:").split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        nativeId.startsWith("itunes:album:") -> {
            val parts = nativeId.removePrefix("itunes:album:").split(":::", limit = 3)
            if (parts.size == 3) parts[1] to parts[2] else null
        }
        nativeId.startsWith("mb:album:") -> {
            val parts = nativeId.removePrefix("mb:album:").split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        else -> null
    }

    private fun fuzzyMatch(candidate: Track, target: Track): Boolean {
        val candidateTitle = candidate.title.normalized()
        val targetTitle = target.title.normalized()
        if (candidateTitle.length < 4 || targetTitle.length < 4) return false

        val candidateWords = candidateTitle.split(" ").toSet()
        val targetWords = targetTitle.split(" ").toSet()
        if (VERSION_WORDS.any { it in candidateWords } && VERSION_WORDS.none { it in targetWords }) return false

        val titlesMatch = candidateTitle == targetTitle
                || candidateTitle.contains(targetTitle)
                || targetTitle.contains(candidateTitle)

        val candidateArtist = normalizeArtistName(candidate.artist)
        val targetArtist = normalizeArtistName(target.artist)

        val artistsMatch = candidateArtist == targetArtist
                || candidateArtist.contains(targetArtist)
                || targetArtist.contains(candidateArtist)
                || candidateArtist.withoutSpaces().contains(targetArtist.withoutSpaces())
                || targetArtist.withoutSpaces().contains(candidateArtist.withoutSpaces())

        val artistNameAppearsInTitle = candidateTitle.withoutSpaces().contains(targetArtist.withoutSpaces())

        return titlesMatch && (artistsMatch || artistNameAppearsInTitle)
    }
}
