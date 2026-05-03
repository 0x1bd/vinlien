package org.kvxd.vinlien.backends

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.SearchResponse
import org.kvxd.vinlien.shared.models.media.Track
import kotlin.math.ln

private val VERSION_WORDS = setOf(
    "acoustic", "live", "instrumental", "karaoke", "cover", "demo", "unplugged", "remix",
    "slowed", "sped", "nightcore", "reverb", "lofi", "mashup"
)

private val ALBUM_QUALIFIER_WORDS = setOf(
    "official", "original", "deluxe", "special", "extended", "limited", "edition", "remastered", "remaster", "feat"
)

class AggregationEngine(val providers: List<MusicProvider>) {

    init {
        BackendDebugger.logProviders(providers)
    }

    private val streamResolver = StreamResolver(providers)

    private fun <T> providerFlow(
        capability: Capability,
        block: suspend (MusicProvider) -> List<T>
    ): Flow<List<T>> = channelFlow {
        providers.filter { capability in it.capabilities }.forEach { provider ->
            launch {
                val result = withTimeoutOrNull(provider.timeoutMs) {
                    Profiler.measure("${provider.name} ${capability.name}") {
                        runCatching { block(provider) }.onFailure { e ->
                            BackendDebugger.logError(provider.id, capability.name, e)
                        }.getOrNull()
                    }
                }
                if (result == null) {
                    BackendDebugger.logTimeout(provider.id, capability.name)
                    return@launch
                }
                if (result.isNotEmpty()) send(result)
            }
        }
    }

    fun searchStreaming(query: String): Flow<SearchResponse> {
        val trackUpdates: Flow<List<Track>> = providerFlow(Capability.TRACK_SEARCH) { it.searchTracks(query) }
            .runningFold(emptyList()) { acc, batch ->
                TrackMerger.merge(acc + batch.map { Normalizer.normalizeTrack(it) })
                    .filter { it.artworkUrl != null }
                    .sortForSearch(query)
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
                    val result = withTimeoutOrNull(provider.timeoutMs) {
                        Profiler.measure("${provider.name} ${capability.name}") {
                            runCatching { block(provider) }.onFailure { e ->
                                BackendDebugger.logError(provider.id, capability.name, e)
                            }.getOrNull()
                        }
                    }
                    if (result == null) BackendDebugger.logTimeout(provider.id, capability.name)
                    result
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    suspend fun searchTracks(query: String): List<Track> {
        val raw = parallelQuery(Capability.TRACK_SEARCH) { it.searchTracks(query) }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.normalizeTrack(it) }).sortForSearch(query)
    }

    suspend fun searchAlbums(query: String): List<Album> {
        val raw = parallelQuery(Capability.ALBUM_SEARCH) { it.searchAlbums(query) }.flatten()
        return AlbumMerger.dedup(raw.map { Normalizer.normalizeAlbum(it) })
    }

    suspend fun getAlbum(nativeId: String): Album? {
        val (artist, title) = AlbumMerger.parseNativeId(nativeId) ?: return null
        val results = parallelQuery(Capability.ALBUM_TRACKS) { it.getAlbum(artist, title) }
        return AlbumMerger.mergeOne(results, nativeId)?.let { Normalizer.normalizeAlbum(it) }
    }

    suspend fun getAlbum(artist: String, title: String): Album? {
        val results = parallelQuery(Capability.ALBUM_TRACKS) { it.getAlbum(artist, title) }
        val dummyId = "merged:album:$artist:::$title"
        val merged = AlbumMerger.mergeOne(results, dummyId)?.let { Normalizer.normalizeAlbum(it) }

        if (merged != null && merged.tracks.isEmpty()) {
            val query = "$artist $title"
            val fallbackTracks = searchTracks(query)
                .filter {
                    val candidateTitle = it.albumTitle ?: it.title
                    candidateTitle.contains(title, ignoreCase = true) || fuzzyMatch(
                        it,
                        Track(id = "", title = title, artist = artist, durationMs = 0)
                    )
                }
            if (fallbackTracks.isNotEmpty()) {
                return merged.copy(tracks = fallbackTracks)
            }
        }
        return merged
    }

    suspend fun getArtistAlbums(artist: String): List<Album> {
        val raw = parallelQuery(Capability.ARTIST_ALBUMS) { it.getArtistAlbums(artist) }.flatten()
        val dedup = AlbumMerger.dedup(raw.map { Normalizer.normalizeAlbum(it) })
        return deduplicateAlbumVariants(dedup)
    }

    suspend fun getArtistTopTracks(artist: String): List<Track> {
        val raw = parallelQuery(Capability.ARTIST_TOP_TRACKS) { it.getArtistTopTracks(artist) }.flatten()
        return TrackMerger.merge(raw.map { Normalizer.normalizeTrack(it) })
            .sortedByDescending { it.popularityScore ?: 0.0 }
    }

    private fun deduplicateAlbumVariants(albums: List<Album>): List<Album> {
        if (albums.size <= 1) return albums
        val normKeys = albums.map { albumNormKey(it.title) }
        val dominated = BooleanArray(albums.size)
        for (i in albums.indices) {
            if (dominated[i]) continue
            for (j in albums.indices) {
                if (i == j || dominated[j]) continue
                val ni = normKeys[i]
                val nj = normKeys[j]
                val sameNormNoYear = ni == nj && albums[i].year == null && albums[j].year != null
                val sameNormBothYear = ni == nj && albums[i].year != null && albums[j].year != null && i > j
                if (sameNormNoYear || sameNormBothYear) {
                    dominated[i] = true
                    break
                }
            }
        }
        return albums.filterIndexed { i, _ -> !dominated[i] }
    }

    private fun albumNormKey(title: String): String {
        val words = title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().split(Regex("\\s+"))
        return words.filter { it !in ALBUM_QUALIFIER_WORDS }.joinToString("")
    }

    suspend fun getArtistInfo(name: String): ArtistInfo? {
        val results = parallelQuery(Capability.ARTIST_INFO) { it.getArtistInfo(name) }
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
                    val needsArtwork = t.artworkUrl == null || t.artworkUrl!!.contains("ytimg.com")
                    if (needsArtwork) enrichWithMetadata(t, metaProviders) else t
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

    private fun List<Track>.sortForSearch(query: String): List<Track> {
        val queryTokens = query.normalized().split(" ").filter { it.length >= 2 }
        if (queryTokens.isEmpty()) return sortedByDescending { it.popularityScore ?: 0.0 }

        val queryNorm = query.normalized()
        val queryCompact = queryNorm.withoutSpaces()

        return sortedWith(
            compareByDescending<Track> { searchRelevanceScore(it, queryTokens, queryNorm, queryCompact) }
                .thenByDescending { normalizedPopularity(it) }
                .thenByDescending { metadataQualityScore(it) }
                .thenBy { it.title.length }
        )
    }

    private fun searchRelevanceScore(
        track: Track,
        queryTokens: List<String>,
        queryNorm: String,
        queryCompact: String
    ): Double {
        val title = track.title.normalized()
        val artist = track.artist.normalized()
        val titleCompact = title.withoutSpaces()
        val artistCompact = artist.withoutSpaces()
        val haystack = "$title $artist"
        val allTokensMatch = queryTokens.all { haystack.contains(it) }
        val compactTokensMatch = queryTokens.all { token ->
            val compactToken = token.withoutSpaces()
            titleCompact.contains(compactToken) || artistCompact.contains(compactToken)
        }
        val titleContainsQuery = title.contains(queryNorm) || titleCompact.contains(queryCompact)
        val artistContainsQuery = artist.contains(queryNorm) || artistCompact.contains(queryCompact)
        val exactArtistQuery = artist == queryNorm || artistCompact == queryCompact
        val exactTitleQuery = title == queryNorm || titleCompact == queryCompact

        return (if (exactArtistQuery) 95.0 else 0.0) +
                (if (artistContainsQuery) 42.0 else 0.0) +
                (if (allTokensMatch || compactTokensMatch) 60.0 else 0.0) +
                queryTokens.count { title.contains(it) } * 8.0 +
                queryTokens.count { artist.contains(it) } * 5.0 +
                queryTokens.count { artistCompact.contains(it.withoutSpaces()) } * 7.0 +
                if (titleContainsQuery) 18.0 else 0.0 +
                        if (exactTitleQuery) 12.0 else 0.0
    }

    private fun normalizedPopularity(track: Track): Double {
        val raw = track.popularityScore ?: return 0.0
        return ln(1.0 + raw.coerceAtLeast(0.0))
    }

    private fun metadataQualityScore(track: Track): Int {
        val artworkUrl = track.artworkUrl
        return (if (artworkUrl != null && !artworkUrl.contains("ytimg.com")) 4 else 0) +
                (if (track.durationMs > 0) 2 else 0) +
                (if (track.lastFmUrl != null) 1 else 0)
    }

    suspend fun enrichArtwork(title: String, artist: String): String? {
        val query = "$artist $title"
        val target = Track(id = "", title = title, artist = artist, durationMs = 0)
        val raw = parallelQuery(Capability.TRACK_SEARCH) { it.searchTracks(query) }.flatten()
        val merged = TrackMerger.merge(raw.map { Normalizer.normalizeTrack(it) })

        return merged.firstOrNull {
            it.artworkUrl != null && !it.artworkUrl!!.contains("ytimg.com") && fuzzyMatch(
                it,
                target
            )
        }?.artworkUrl
            ?: merged.firstOrNull { it.artworkUrl != null && fuzzyMatch(it, target) }?.artworkUrl
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
                canonicalId = match.canonicalId ?: track.canonicalId,
                albumTitle = match.albumTitle ?: track.albumTitle,
                albumId = match.albumId ?: track.albumId
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

    private fun fuzzyMatch(candidate: Track, target: Track): Boolean {
        val candidateTitle = candidate.title.normalized()
        val targetTitle = target.title.normalized()
        if (candidateTitle.isEmpty() || targetTitle.isEmpty()) return false

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
