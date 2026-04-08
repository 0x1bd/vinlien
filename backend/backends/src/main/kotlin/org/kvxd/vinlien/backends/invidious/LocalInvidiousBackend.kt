package org.kvxd.vinlien.backends.invidious

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.AudioProvider
import org.kvxd.vinlien.backends.MetadataProvider
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Track
import org.slf4j.LoggerFactory
import java.net.URLEncoder

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
        val trackTitle = title ?: return null
        val durationMs = (lengthSeconds ?: 0L) * 1000
        if (durationMs > 600_000L) return null
        val artist = author ?: "Unknown Artist"

        return Track(
            id = id,
            title = trackTitle,
            artist = artist,
            durationMs = durationMs,
            artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            canonicalId = "${artist.lowercase().trim()}:::${trackTitle.lowercase().trim()}"
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

class LocalInvidiousBackend(private val instanceUrl: String = "http://localhost:3000") : MetadataProvider,
    AudioProvider {

    override val name = "Invidious"
    private val logger = LoggerFactory.getLogger(LocalInvidiousBackend::class.java)

    private fun api(path: String) = "$instanceUrl/api/v1/$path"

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val jsonString = fetch(api("search?q=${query.encoded}&type=video"))
        val videos = sharedJson.decodeFromString<List<InvidiousVideo>>(jsonString)
        videos.mapNotNull { it.toTrack() }
    }

    override suspend fun searchAudio(query: String): List<Track> = search(query)

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val jsonString = fetch(api("videos/${resolveVideoId(track)}"))
        val details = sharedJson.decodeFromString<InvidiousVideoDetails>(jsonString)
        details.recommendedVideos.mapNotNull { it.toTrack() }
    }

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        val jsonString = fetch(api("search?q=${"pop hits".encoded}&type=video"))
        val videos = sharedJson.decodeFromString<List<InvidiousVideo>>(jsonString)
        videos.mapNotNull { it.toTrack() }.take(6)
    }

    override suspend fun getStreamUrl(track: Track): String = withContext(Dispatchers.IO) {
        if (track.id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return@withContext fetchAudioFromId(track.id)
        }

        val scrapedId = scrapeLastFmVideoId(track)
        if (scrapedId != null) {
            return@withContext fetchAudioFromId(scrapedId)
        }

        throw Exception("Not a native Invidious ID and Last.fm scrape failed.")
    }

    private suspend fun fetchAudioFromId(id: String): String {
        val jsonString = fetch(api("videos/$id"))
        val video = sharedJson.decodeFromString<InvidiousVideoDetails>(jsonString)

        if (video.error != null) {
            throw Exception("Invidious returned error for $id: ${video.error}")
        }

        val streams = video.adaptiveFormats + video.formatStreams
        val audio = streams.firstOrNull { it.itag == "140" }
            ?: streams.firstOrNull { it.type?.startsWith("audio/") == true }
            ?: throw Exception("No audio stream found for video ID $id")

        return audio.url ?: throw Exception("No URL in audio stream for $id")
    }

    private suspend fun resolveVideoId(track: Track): String {
        if (track.id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return track.id
        return scrapeLastFmVideoId(track) ?: throw Exception("Could not resolve video ID for ${track.title}")
    }

    private suspend fun scrapeLastFmVideoId(track: Track): String? {
        val urls = buildList {
            track.lastFmUrl?.let { add(it) }
            add("https://www.last.fm/music/${track.artist.encoded}/_/${track.title.encoded}")
        }.distinct()

        for (url in urls) {
            try {
                val html = fetch(
                    url, mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Cookie" to "notice_preferences=2:1a8b5c6d; notice_gdpr_prefs=0,1,2:1a8b5c6d"
                    )
                )
                val id = Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})""").find(html)?.groupValues?.get(1)
                if (id != null) {
                    logger.debug("Last.fm scrape OK '${track.artist} - ${track.title}': $id"); return id
                }
            } catch (e: Exception) {
                logger.warn("Last.fm scrape failed for $url: ${e.message}")
            }
        }
        return null
    }

    private val String.encoded get() = URLEncoder.encode(this, "UTF-8")
}