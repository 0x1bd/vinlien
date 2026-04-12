package org.kvxd.vinlien.backends.soundcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.models.Track
import org.slf4j.LoggerFactory
import java.net.URLEncoder

@Serializable
private data class ScSearchResponse(val collection: List<ScTrack> = emptyList())

@Serializable
private data class ScTrendingResponse(val collection: List<ScTrendingItem> = emptyList())

@Serializable
private data class ScTrendingItem(val track: ScTrack? = null)

@Serializable
private data class ScTrack(
    val id: Long? = null,
    val title: String? = null,
    val duration: Long? = null,
    val policy: String? = null,
    val artwork_url: String? = null,
    val user: ScUser? = null,
    val media: ScMedia? = null
) {
    fun toDomainTrack(): Track? {
        val trackTitle = title ?: return null
        val artist = user?.username ?: "Unknown"
        val trackId = id?.toString() ?: return null
        val durationMs = duration ?: 0L

        if (policy?.uppercase() == "SNIPPET") return null
        if (durationMs in 1..45_000L) return null

        val artworkUrl = artwork_url?.replace("large", "t500x500")
            ?: user?.avatar_url?.replace("large", "t500x500")
            ?: "https://ui-avatars.com/api/?name=${URLEncoder.encode(artist, "UTF-8")}&background=random"

        val streamUrl = media?.transcodings?.firstOrNull {
            it.format?.protocol == "progressive" && it.snipped != true
        }?.url ?: return null

        return Track(
            id = "sc:$trackId",
            title = trackTitle,
            artist = artist,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            streamUrl = streamUrl,
            canonicalId = Normalizer.canonicalIdFor(artist, trackTitle)
        )
    }
}

@Serializable
private data class ScUser(val username: String? = null, val avatar_url: String? = null)

@Serializable
private data class ScMedia(val transcodings: List<ScTranscoding> = emptyList())

@Serializable
private data class ScTranscoding(
    val format: ScFormat? = null,
    val snipped: Boolean? = null,
    val url: String? = null
)

@Serializable
private data class ScFormat(val protocol: String? = null)

@Serializable
private data class ScStreamResponse(val url: String? = null)


class SoundCloudBackend : MusicProvider {
    override val id = "sc"
    override val name = "SoundCloud"
    override val capabilities = setOf(Capability.TRACK_SEARCH, Capability.TRENDING, Capability.AUDIO_STREAM)

    private val logger = LoggerFactory.getLogger(SoundCloudBackend::class.java)
    private var clientId: String? = null
    private val mutex = Mutex()

    private suspend fun getClientId(): String = mutex.withLock {
        clientId?.let { return it }
        try {
            val html = fetch("https://soundcloud.com")
            val scriptUrls = Regex("""<script.*?src="(https://a-v2\.sndcdn\.com/assets/[^"]+)"""")
                .findAll(html).map { it.groupValues[1] }.toList()

            for (url in scriptUrls.reversed()) {
                val js = fetch(url)
                val match = Regex("""client_id:"([a-zA-Z0-9]{32})"""").find(js)
                if (match != null) {
                    clientId = match.groupValues[1]
                    logger.info("Found SoundCloud client_id: $clientId")
                    return clientId!!
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to scrape SoundCloud client_id: ${e.message}")
        }
        return "1r2g3h4j5k6l7m8n9o0p"
    }

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            sharedJson.decodeFromString<ScSearchResponse>(
                fetch("https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=${getClientId()}&limit=15")
            ).collection.mapNotNull { it.toDomainTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun searchAudio(query: String): List<Track> = searchTracks(query)

    override suspend fun getTrending(): List<Track> = withContext(Dispatchers.IO) {
        try {
            sharedJson.decodeFromString<ScTrendingResponse>(
                fetch("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud:genres:all-music&client_id=${getClientId()}&limit=10")
            ).collection.mapNotNull { it.track?.toDomainTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolveStream(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            if (!track.id.startsWith("sc:") || track.streamUrl == null) return@withContext null
            val res = sharedJson.decodeFromString<ScStreamResponse>(
                fetch("${track.streamUrl}?client_id=${getClientId()}")
            )
            res.url
        } catch (e: Exception) {
            null
        }
    }
}
