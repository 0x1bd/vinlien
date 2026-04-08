package org.kvxd.vinlien.backends

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

class BackendManager(
    val metadataProviders: List<MetadataProvider>,
    val audioProviders: List<AudioProvider>
) {
    suspend fun search(query: String, preferred: String? = null): List<Track> =
        executeWithFallback(preferred, metadataProviders) { it.search(query) } ?: emptyList()

    suspend fun getRecommendations(track: Track, preferred: String? = null): List<Track> =
        executeWithFallback(preferred, metadataProviders) { it.getRecommendations(track) } ?: emptyList()

    suspend fun getTrending(preferred: String? = null): List<Track> =
        executeWithFallback(preferred, metadataProviders) { it.getTrending() } ?: emptyList()

    suspend fun getArtistInfo(name: String, preferred: String? = null): ArtistInfo? =
        executeWithFallback(preferred, metadataProviders) { it.getArtistInfo(name) }

    suspend fun searchAlbums(query: String, preferred: String? = null): List<Album> =
        executeWithFallback(preferred, metadataProviders) { it.searchAlbums(query) } ?: emptyList()

    suspend fun getArtistAlbums(artist: String, preferred: String? = null): List<Album> =
        executeWithFallback(preferred, metadataProviders) { it.getArtistAlbums(artist) } ?: emptyList()

    suspend fun getAlbum(id: String, preferred: String? = null): Album? {
        val targetProvider = metadataProviders.find { id.startsWith(prefixFor(it.name), ignoreCase = true) }
        if (targetProvider != null) {
            val result = runCatching { targetProvider.getAlbum(id) }.getOrNull()
            if (result != null) return result
        }
        return executeWithFallback(preferred, metadataProviders) { it.getAlbum(id) }
    }

    suspend fun getStreamUrl(track: Track, preferred: String? = null): String = withContext(Dispatchers.IO) {
        val nativePrefix = track.id.substringBefore(":")
        val nativeProvider = audioProviders.find { prefixFor(it.name).equals(nativePrefix, ignoreCase = true) }

        if (nativeProvider != null) {
            runCatching { nativeProvider.getStreamUrl(track) }.getOrNull()?.let { return@withContext it }
        }

        val primary = audioProviders.find { it.name.equals(preferred, ignoreCase = true) } ?: audioProviders.first()
        val candidates = listOf(primary) + audioProviders.filter { it != primary && it != nativeProvider }

        for (provider in candidates) {
            val results = runCatching { provider.searchAudio("${track.artist} ${track.title}") }.getOrNull() ?: continue
            val match = results.firstOrNull {
                it.canonicalId == track.canonicalId ||
                        (it.artist.equals(track.artist, ignoreCase = true) && it.title.equals(
                            track.title,
                            ignoreCase = true
                        ))
            } ?: results.firstOrNull()

            if (match != null) {
                runCatching { provider.getStreamUrl(match) }.getOrNull()?.let { return@withContext it }
            }
        }
        throw Exception("No stream available across all fallback providers for: ${track.title}")
    }

    private suspend fun <T, P : Any> executeWithFallback(
        preferred: String?,
        providers: List<P>,
        action: suspend (P) -> T?
    ): T? {
        val primary = providers.find {
            (it as? MetadataProvider)?.name.equals(preferred, true) ||
                    (it as? AudioProvider)?.name.equals(preferred, true)
        } ?: providers.firstOrNull() ?: return null

        val primaryResult = runCatching { action(primary) }.getOrNull()
        if (isValidResult(primaryResult)) return primaryResult

        for (provider in providers) {
            if (provider == primary) continue
            val result = runCatching { action(provider) }.getOrNull()
            if (isValidResult(result)) return result
        }
        return null
    }

    private fun <T> isValidResult(result: T?): Boolean {
        if (result == null) return false
        if (result is List<*> && result.isEmpty()) return false
        return true
    }

    private fun prefixFor(name: String): String = when (name.lowercase()) {
        "soundcloud" -> "sc"
        "last.fm" -> "lastfm"
        "itunes" -> "itunes"
        "musicbrainz" -> "mb"
        "invidious" -> "invidious"
        else -> name.lowercase()
    }
}