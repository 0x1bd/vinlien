package org.kvxd.vinlien.backends

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kvxd.vinlien.backends.invidious.LocalInvidiousBackend
import org.kvxd.vinlien.backends.itunes.ItunesMetadataProvider
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

class BackendManager(
    val metadataProviders: List<MetadataProvider>,
    val audioProviders: List<AudioProvider>
) {
    suspend fun search(query: String, preferred: String? = null): List<Track> = withContext(Dispatchers.IO) {
        val primary = metadataProviders.find { it.name.equals(preferred, true) }
        val ordered = if (primary != null) listOf(primary) + metadataProviders.filter { it != primary }
        else metadataProviders
        ordered.filter { it.searchable }.map { provider ->
            async {
                withTimeoutOrNull(5_000) {
                    runCatching { provider.search(query) }.getOrNull()
                } ?: emptyList()
            }
        }.awaitAll().flatten().map { Normalizer.track(it) }
    }

    suspend fun getRecommendations(track: Track, preferred: String? = null): List<Track> =
        (executeWithFallback(preferred, metadataProviders) { it.getRecommendations(track) } ?: emptyList())
            .map { Normalizer.track(it) }

    suspend fun getTrending(preferred: String? = null): List<Track> =
        (executeWithFallback(preferred, metadataProviders) { it.getTrending() } ?: emptyList())
            .map { Normalizer.track(it) }

    suspend fun getArtistInfo(name: String, preferred: String? = null): ArtistInfo? {
        val info = executeWithFallback(preferred, metadataProviders) { it.getArtistInfo(name) }
            ?: return null

        if (info.imageUrl != null) return info

        val itunes = metadataProviders.filterIsInstance<ItunesMetadataProvider>().firstOrNull()
        val fallbackImage = if (itunes != null) {
            runCatching { itunes.getArtistAlbums(name) }.getOrNull()?.firstOrNull()?.artworkUrl
        } else null

        return if (fallbackImage != null) info.copy(imageUrl = fallbackImage) else info
    }

    suspend fun searchAlbums(query: String, preferred: String? = null): List<Album> =
        (executeWithFallback(preferred, metadataProviders) { it.searchAlbums(query) } ?: emptyList())
            .map { Normalizer.album(it) }

    suspend fun getArtistAlbums(artist: String, preferred: String? = null): List<Album> {
        val albums = executeWithFallback(preferred, metadataProviders) { it.getArtistAlbums(artist) }
            ?: emptyList()

        if (albums.none { it.artworkUrl == null }) return albums.map { Normalizer.album(it) }

        val itunes = metadataProviders.filterIsInstance<ItunesMetadataProvider>().firstOrNull()
            ?: return albums.filter { it.artworkUrl != null }.map { Normalizer.album(it) }

        val itunesAlbums = runCatching { itunes.getArtistAlbums(artist) }.getOrNull() ?: emptyList()

        return albums.mapNotNull { album ->
            if (album.artworkUrl != null) return@mapNotNull album
            val match = itunesAlbums.firstOrNull { it.title.equals(album.title, ignoreCase = true) }
            match?.artworkUrl?.let { album.copy(artworkUrl = it, year = match.year ?: album.year) }
        }.map { Normalizer.album(it) }
    }

    suspend fun getAlbum(id: String, preferred: String? = null): Album? {
        val targetProvider = metadataProviders.find { id.startsWith(prefixFor(it.name), ignoreCase = true) }
        var album = if (targetProvider != null) {
            runCatching { targetProvider.getAlbum(id) }.getOrNull()
        } else null

        if (album == null) {
            album = executeWithFallback(preferred, metadataProviders) { it.getAlbum(id) }
        }

        if (album != null && id.startsWith("lastfm:album:", ignoreCase = true)) {
            val itunes = metadataProviders.filterIsInstance<ItunesMetadataProvider>().firstOrNull()
            if (itunes != null) {
                val match = runCatching { itunes.searchAlbums("${album.artist} ${album.title}") }.getOrNull()
                    ?.firstOrNull {
                        it.title.equals(album.title, ignoreCase = true) &&
                                it.artist.equals(album.artist, ignoreCase = true)
                    }
                if (match?.year != null) album = album.copy(year = match.year)
            }
        }

        return album?.let { Normalizer.album(it) }
    }

    suspend fun getStreamUrl(track: Track, preferred: String? = null): String = withContext(Dispatchers.IO) {
        val nativePrefix = track.id.substringBefore(":")
        val nativeProvider = audioProviders.find { prefixFor(it.name).equals(nativePrefix, ignoreCase = true) }

        if (nativeProvider != null) {
            runCatching { nativeProvider.getStreamUrl(track) }.getOrNull()?.let { return@withContext it }
        }

        val primary = audioProviders.find { it.name.equals(preferred, ignoreCase = true) } ?: audioProviders.first()
        val candidates = listOf(primary) + audioProviders.filter { it != primary && it != nativeProvider }

        if (track.id.startsWith("lastfm:")) {
            val invidious = audioProviders.filterIsInstance<LocalInvidiousBackend>().firstOrNull()
            val directUrl = invidious?.let { runCatching { it.streamFromLastFm(track) }.getOrNull() }
            if (directUrl != null) return@withContext directUrl
        }

        val searchArtist = track.artist
            .replace(Regex("""\s+(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "")
            .trim()

        for (provider in candidates) {
            val results =
                runCatching { provider.searchAudio("$searchArtist ${track.title}") }.getOrNull() ?: emptyList()
            val match = results.firstOrNull {
                it.canonicalId == track.canonicalId ||
                        (it.artist.equals(track.artist, ignoreCase = true) && it.title.equals(
                            track.title,
                            ignoreCase = true
                        )) ||
                        fuzzyTrackMatch(it, track)
            }

            val url = if (match != null) {
                runCatching { provider.getStreamUrl(match) }.getOrNull()
            } else {
                runCatching { provider.getStreamUrl(track) }.getOrNull()
            }
            url?.let { return@withContext it }
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

    private fun fuzzyTrackMatch(candidate: Track, target: Track): Boolean {
        fun normalizeTitle(s: String) = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim().replace(Regex("\\s+"), " ")

        fun normalizeArtist(s: String) = s.lowercase()
            .replace(Regex("""\s+(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim().replace(Regex("\\s+"), " ")

        val candTitle = normalizeTitle(candidate.title)
        val targTitle = normalizeTitle(target.title)
        if (candTitle.length < 4 || targTitle.length < 4) return false

        val versionWords = setOf("acoustic", "live", "instrumental", "karaoke", "cover", "demo", "unplugged", "remix")
        val candHasVersion = versionWords.any { w -> candTitle.split(" ").contains(w) }
        val targHasVersion = versionWords.any { w -> targTitle.split(" ").contains(w) }
        if (candHasVersion && !targHasVersion) return false

        val titleMatch = candTitle == targTitle ||
                candTitle.contains(targTitle) ||
                targTitle.contains(candTitle)

        val candArtist = normalizeArtist(candidate.artist)
        val targArtist = normalizeArtist(target.artist)
        val artistMatch = candArtist == targArtist ||
                candArtist.contains(targArtist) ||
                targArtist.contains(candArtist)

        return titleMatch && artistMatch
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