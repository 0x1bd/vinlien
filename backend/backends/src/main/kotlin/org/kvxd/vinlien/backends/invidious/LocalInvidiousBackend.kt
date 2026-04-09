package org.kvxd.vinlien.backends.invidious

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Track
import org.slf4j.LoggerFactory
import java.net.URLEncoder

private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

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


class LocalInvidiousBackend(private val instanceUrl: String = "http://localhost:3000") : MusicProvider {
    override val id = "invidious"
    override val name = "Invidious"
    override val capabilities = setOf(Capability.RECOMMENDATIONS, Capability.AUDIO_STREAM)

    private val logger = LoggerFactory.getLogger(LocalInvidiousBackend::class.java)

    private fun api(path: String) = "$instanceUrl/api/v1/$path"

    private suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        sharedJson.decodeFromString<List<InvidiousVideo>>(
            fetch(api("search?q=${URLEncoder.encode(query, "UTF-8")}&type=video"))
        ).mapNotNull { it.toTrack() }
    }

    override suspend fun searchAudio(query: String): List<Track> = search(query)

    override suspend fun getRecommendations(track: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            val videoId: String = when {
                track.id.matches(YT_ID_REGEX) -> track.id
                else -> {
                    val primaryArtist = Normalizer.primaryArtist(track)
                    search("$primaryArtist ${track.title}").firstOrNull()?.id
                        ?: return@withContext emptyList()
                }
            }
            sharedJson.decodeFromString<InvidiousVideoDetails>(fetch(api("videos/$videoId")))
                .recommendedVideos.mapNotNull { it.toTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolveStream(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            when {
                track.id.matches(YT_ID_REGEX) -> fetchAudioFromId(track.id)
                else -> scrapeLastFmVideoId(track)?.let { fetchAudioFromId(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchAudioFromId(id: String): String {
        val video = sharedJson.decodeFromString<InvidiousVideoDetails>(fetch(api("videos/$id")))
        if (video.error != null) throw Exception("Invidious returned error for $id: ${video.error}")
        val streams = video.adaptiveFormats + video.formatStreams
        val audio = streams.firstOrNull { it.itag == "140" }
            ?: streams.firstOrNull { it.type?.startsWith("audio/") == true }
            ?: throw Exception("No audio stream found for $id")
        return audio.url ?: throw Exception("No URL in audio stream for $id")
    }

    private suspend fun scrapeLastFmVideoId(track: Track): String? {
        val urls = buildList {
            track.lastFmUrl?.let { add(it) }
            add(
                "https://www.last.fm/music/${
                    URLEncoder.encode(
                        track.artist,
                        "UTF-8"
                    )
                }/_/${URLEncoder.encode(track.title, "UTF-8")}"
            )
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
                val id = listOf(
                    Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""),
                    Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
                    Regex(""""youtube_id"\s*:\s*"([a-zA-Z0-9_-]{11})""""),
                    Regex("""data-youtube-id="([a-zA-Z0-9_-]{11})"""")
                ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
                if (id != null) {
                    logger.debug("Last.fm scrape OK '${track.artist} - ${track.title}': $id")
                    return id
                }
            } catch (e: Exception) {
                logger.warn("Last.fm scrape failed for $url: ${e.message}")
            }
        }
        return null
    }
}
