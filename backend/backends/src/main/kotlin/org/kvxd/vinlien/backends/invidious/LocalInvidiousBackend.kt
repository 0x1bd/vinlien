package org.kvxd.vinlien.backends.invidious

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.YT_ID_REGEX
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Track
import org.slf4j.LoggerFactory
import java.net.URLEncoder

private val YOUTUBE_ID_PATTERNS = listOf(
    Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""),
    Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
    Regex(""""youtube_id"\s*:\s*"([a-zA-Z0-9_-]{11})""""),
    Regex("""data-youtube-id="([a-zA-Z0-9_-]{11})"""")
)

private val LAST_FM_BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept-Language" to "en-US,en;q=0.9",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Cookie" to "notice_preferences=2:1a8b5c6d; notice_gdpr_prefs=0,1,2:1a8b5c6d"
)

private const val MAX_TRACK_DURATION_MS = 600_000L

@Serializable
private data class InvidiousVideo(
    val title: String? = null,
    val videoId: String? = null,
    val author: String? = null,
    val lengthSeconds: Long? = null,
    val type: String? = null
) {
    fun toTrack(): Track? {
        if (type != null && type != "video") return null
        val id = videoId ?: return null
        val rawTitle = title ?: return null
        val rawAuthor = author ?: "Unknown Artist"
        val durationMs = (lengthSeconds ?: 0L) * 1000
        if (durationMs > MAX_TRACK_DURATION_MS) return null

        val artistTitleSeparatorIndex = rawTitle.indexOf(" - ")
        val (trackArtist, trackTitle) = if (artistTitleSeparatorIndex > 0) {
            rawTitle.substring(0, artistTitleSeparatorIndex).trim() to
                    rawTitle.substring(artistTitleSeparatorIndex + 3).trim()
        } else {
            rawAuthor to rawTitle
        }

        return Track(
            id = id,
            title = trackTitle,
            artist = trackArtist,
            durationMs = durationMs,
            artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            canonicalId = Normalizer.canonicalIdFor(trackArtist, trackTitle)
        )
    }
}

@Serializable
private data class InvidiousVideoDetails(
    val recommendedVideos: List<InvidiousVideo> = emptyList(),
    val adaptiveFormats: List<InvidiousStream> = emptyList(),
    val formatStreams: List<InvidiousStream> = emptyList(),
    val error: String? = null
)

@Serializable
private data class InvidiousStream(
    val itag: String? = null,
    val type: String? = null,
    val url: String? = null
)


class LocalInvidiousBackend(private val instanceUrl: String = "http://localhost:3000") : MusicProvider {
    override val id = "invidious"
    override val name = "Invidious"
    override val capabilities = setOf(Capability.AUDIO_STREAM)

    private val logger = LoggerFactory.getLogger(LocalInvidiousBackend::class.java)

    private fun apiUrl(path: String) = "$instanceUrl/api/v1/$path"

    private suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        sharedJson.decodeFromString<List<InvidiousVideo>>(
            fetch(apiUrl("search?q=${URLEncoder.encode(query, "UTF-8")}&type=video"))
        ).mapNotNull { it.toTrack() }
    }

    override suspend fun searchAudio(query: String): List<Track> = search(query)

    override suspend fun resolveStream(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            when {
                track.id.matches(YT_ID_REGEX) -> fetchAudioStreamUrl(track.id)
                else -> scrapeLastFmForYoutubeId(track)?.let { fetchAudioStreamUrl(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchAudioStreamUrl(videoId: String): String {
        val video = sharedJson.decodeFromString<InvidiousVideoDetails>(fetch(apiUrl("videos/$videoId")))
        if (video.error != null) throw Exception("Invidious returned error for $videoId: ${video.error}")
        val streams = video.adaptiveFormats + video.formatStreams
        val audioStream = streams.firstOrNull { it.itag == "140" }
            ?: streams.firstOrNull { it.type?.startsWith("audio/") == true }
            ?: throw Exception("No audio stream found for $videoId")
        return audioStream.url ?: throw Exception("No URL in audio stream for $videoId")
    }

    private suspend fun scrapeLastFmForYoutubeId(track: Track): String? {
        val candidateUrls = buildLastFmCandidateUrls(track)
        return candidateUrls.firstNotNullOfOrNull { url -> fetchYoutubeIdFromLastFmPage(url, track) }
    }

    private fun buildLastFmCandidateUrls(track: Track): List<String> = listOfNotNull(
        track.lastFmUrl,
        "https://www.last.fm/music/${URLEncoder.encode(track.artist, "UTF-8")}/_/${URLEncoder.encode(track.title, "UTF-8")}"
    ).distinct()

    private suspend fun fetchYoutubeIdFromLastFmPage(url: String, track: Track): String? {
        return try {
            val html = fetch(url, LAST_FM_BROWSER_HEADERS)
            val youtubeId = YOUTUBE_ID_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(html)?.groupValues?.get(1)
            }
            if (youtubeId != null) {
                logger.debug("Last.fm scrape OK '${track.artist} - ${track.title}': $youtubeId")
            }
            youtubeId
        } catch (e: Exception) {
            logger.warn("Last.fm scrape failed for $url: ${e.message}")
            null
        }
    }
}
