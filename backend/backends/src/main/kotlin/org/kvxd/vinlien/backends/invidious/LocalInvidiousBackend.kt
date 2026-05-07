package org.kvxd.vinlien.backends.invidious

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.*
import org.kvxd.vinlien.shared.models.media.Track
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

    override suspend fun searchAudio(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val rawResponse = fetch(apiUrl("search?q=${URLEncoder.encode(query, "UTF-8")}&type=video"))
            val videos = sharedJson.decodeFromString<List<InvidiousVideo>>(rawResponse)
            logger.debug("Invidious search returned {} raw results for '{}'", videos.size, query)

            val rejectedReasons = mutableListOf<String>()
            val sortedVideos = videos.sortedByDescending { video ->
                if (video.author?.endsWith(" - Topic", ignoreCase = true) == true) 1 else 0
            }

            val tracks = sortedVideos.mapNotNull { video ->
                val track = video.toTrack()
                if (track == null) {
                    val reason = when {
                        video.type != null && video.type != "video" -> "type='${video.type}' (not video)"
                        video.videoId == null -> "missing videoId"
                        video.title == null -> "missing title"
                        (video.lengthSeconds ?: 0L) * 1000 > MAX_TRACK_DURATION_MS -> "duration=${video.lengthSeconds}s exceeds max"
                        else -> "toTrack() returned null"
                    }
                    rejectedReasons.add("[${video.videoId ?: "no-id"}] ${video.title ?: "(no title)"}: $reason")
                }
                track
            }

            if (rejectedReasons.isNotEmpty()) {
                logger.debug("Invidious search rejected {} results for '{}':\n  {}",
                    rejectedReasons.size, query, rejectedReasons.joinToString("\n  "))
            }
            logger.debug("Invidious search returned {} valid tracks for '{}'", tracks.size, query)
            tracks
        } catch (e: Exception) {
            logger.warn("Invidious search failed for query '{}': {}", query, e.message)
            emptyList()
        }
    }

    override suspend fun resolveStream(track: Track): StreamResolutionResult = withContext(Dispatchers.IO) {
        try {
            val youtubeId = when {
                track.id.matches(YT_ID_REGEX) -> {
                    logger.debug("Invidious: direct YouTube ID '{}'", track.id)
                    track.id
                }
                else -> {
                    logger.debug("Invidious: scraping Last.fm for '{} - {}'", track.artist, track.title)
                    scrapeLastFmForYoutubeId(track).also { ytId ->
                        if (ytId != null) {
                            logger.debug("Invidious: Last.fm returned YouTube ID '{}'", ytId)
                        } else {
                            logger.debug("Invidious: Last.fm returned no YouTube ID")
                        }
                    } ?: return@withContext StreamResolutionResult.Failure(
                        providerId = id,
                        reason = "Last.fm scrape returned no YouTube ID for '${track.artist} - ${track.title}'"
                    )
                }
            }

            val streamUrl = fetchAudioStreamUrl(youtubeId)
            logger.info("Invidious: resolved stream for '{} - {}' (ytId={})", track.artist, track.title, youtubeId)
            StreamResolutionResult.Success(streamUrl, id)
        } catch (e: Exception) {
            logger.warn("Invidious: resolveStream failed for '{} - {}' (id={}): {}",
                track.artist, track.title, track.id, e.message)
            StreamResolutionResult.Failure(
                providerId = id,
                reason = "Invidious: ${e.message ?: e::class.simpleName}",
                cause = e
            )
        }
    }

    private suspend fun fetchAudioStreamUrl(videoId: String): String {
        val rawResponse = fetch(apiUrl("videos/$videoId"))
        val video = sharedJson.decodeFromString<InvidiousVideoDetails>(rawResponse)

        if (video.error != null) {
            throw Exception("Invidious API error for $videoId: ${video.error}")
        }

        val allStreams = video.adaptiveFormats + video.formatStreams
        logger.debug("Invidious: video '{}' has {} adaptive + {} format streams",
            videoId, video.adaptiveFormats.size, video.formatStreams.size)

        if (allStreams.isEmpty()) {
            throw Exception("No streams available for video $videoId")
        }

        val streamDetails = allStreams.map { s ->
            "itag=${s.itag ?: "-"}, type=${s.type ?: "-"}, url=${if (s.url != null) "yes" else "no"}"
        }.joinToString("; ")
        logger.debug("Invidious: streams for '{}': [{}]", videoId, streamDetails)

        val audioStream = allStreams.firstOrNull { it.itag == "140" }
            ?: allStreams.firstOrNull { it.type?.startsWith("audio/") == true }
            ?: throw Exception("No audio stream for $videoId (types: ${allStreams.mapNotNull { it.type }.distinct().take(5).joinToString(", ")})")

        return audioStream.url
            ?: throw Exception("Audio stream for $videoId has no URL (itag=${audioStream.itag}, type=${audioStream.type})")
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
                logger.debug("Last.fm: found YouTube ID '{}' for '{} - {}'", youtubeId, track.artist, track.title)
            }
            youtubeId
        } catch (e: Exception) {
            logger.debug("Last.fm: scrape failed for {}: {}", url, e.message)
            null
        }
    }
}
