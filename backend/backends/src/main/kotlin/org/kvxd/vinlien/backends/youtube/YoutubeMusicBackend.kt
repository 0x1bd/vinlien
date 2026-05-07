package org.kvxd.vinlien.backends.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.kvxd.vinlien.backends.*
import org.kvxd.vinlien.shared.models.media.Track
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val YTM_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
private const val YTM_BASE_API = "https://music.youtube.com/youtubei/v1"
private const val YTM_SONG_SEARCH_PARAMS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
private const val YTM_VIDEO_SEARCH_PARAMS = "EgWKAQIQAWoMEA4QChADEAQQCRAF"
private const val MAX_TRACK_DURATION_MS = 600_000L
private val YTM_CLIENT_VERSION_DATE = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC))

private val VISUALIZER_TERMS = Regex(
    """\b(official\s+visuali[sz]er|visuali[sz]er)\b""",
    RegexOption.IGNORE_CASE
)

@Serializable
private data class YoutubeMusicSearchRequest(
    val context: YoutubeMusicContext,
    val query: String,
    val params: String
)

@Serializable
private data class YoutubeMusicContext(
    val client: YoutubeMusicClient,
    val user: Map<String, String>
)

@Serializable
private data class YoutubeMusicClient(
    val clientName: String,
    val clientVersion: String,
    val hl: String,
    val gl: String
)

@Serializable
private data class StreamExtractorVideoDetails(
    val adaptiveFormats: List<StreamExtractorFormat> = emptyList(),
    val formatStreams: List<StreamExtractorFormat> = emptyList(),
    val error: String? = null
)

@Serializable
private data class StreamExtractorFormat(
    val itag: String? = null,
    val type: String? = null,
    val url: String? = null
)

internal data class YoutubeMusicSearchResult(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val albumTitle: String?,
    val durationMs: Long,
    val videoType: String?
) {
    fun toTrack(): Track? {
        if (durationMs > MAX_TRACK_DURATION_MS) return null
        val artist = artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        return Track(
            id = "ytmusic:$videoId",
            title = title,
            artist = artist,
            artists = artists,
            durationMs = durationMs,
            artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            canonicalId = Normalizer.canonicalIdFor(artist, title),
            albumTitle = albumTitle
        )
    }
}

class YoutubeMusicBackend(private val invidiousInstanceUrl: String = "http://localhost:3000") : MusicProvider {
    override val id = "ytmusic"
    override val name = "YouTube Music"
    override val capabilities = setOf(Capability.AUDIO_STREAM)

    private val logger = LoggerFactory.getLogger(YoutubeMusicBackend::class.java)

    override suspend fun searchAudio(query: String): List<Track> = withContext(Dispatchers.IO) {
        val songResults = runCatching { search(query, YTM_SONG_SEARCH_PARAMS) }
            .onFailure { logger.warn("YouTube Music song search failed for '{}': {}", query, it.message) }
            .getOrDefault(emptyList())

        val videoResults = runCatching { search(query, YTM_VIDEO_SEARCH_PARAMS) }
            .onFailure { logger.warn("YouTube Music video search failed for '{}': {}", query, it.message) }
            .getOrDefault(emptyList())

        (songResults + videoResults)
            .distinctBy { it.videoId }
            .sortedWith(
                compareBy<YoutubeMusicSearchResult> { if (it.videoType == "MUSIC_VIDEO_TYPE_ATV") 0 else 1 }
                    .thenBy { if (VISUALIZER_TERMS.containsMatchIn(it.title)) 1 else 0 }
            )
            .mapNotNull { it.toTrack() }
            .also { logger.debug("YouTube Music returned {} tracks for '{}'", it.size, query) }
    }

    override suspend fun resolveStream(track: Track): StreamResolutionResult = withContext(Dispatchers.IO) {
        val videoId = when {
            track.id.startsWith("ytmusic:") -> track.id.substringAfter(":")
            track.id.matches(YT_ID_REGEX) -> track.id
            else -> return@withContext StreamResolutionResult.Failure(
                providerId = id,
                reason = "Track id '${track.id}' is not a YouTube Music or YouTube video id"
            )
        }

        try {
            StreamResolutionResult.Success(fetchAudioStreamUrl(videoId), id)
        } catch (e: Exception) {
            logger.warn("YouTube Music: resolveStream failed for '{}' (id={}): {}", track.title, track.id, e.message)
            StreamResolutionResult.Failure(
                providerId = id,
                reason = "YouTube Music stream extractor: ${e.message ?: e::class.simpleName}",
                cause = e
            )
        }
    }

    private suspend fun search(query: String, params: String): List<YoutubeMusicSearchResult> {
        val rawResponse = postJson(
            "$YTM_BASE_API/search?alt=json&key=$YTM_API_KEY",
            buildYoutubeMusicSearchRequestBody(query, params),
            mapOf(
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/"
            )
        )

        return parseYoutubeMusicSearchResults(sharedJson.parseToJsonElement(rawResponse))
    }

    private suspend fun fetchAudioStreamUrl(videoId: String): String {
        val rawResponse = fetch("$invidiousInstanceUrl/api/v1/videos/$videoId")
        val video = sharedJson.decodeFromString<StreamExtractorVideoDetails>(rawResponse)

        if (video.error != null) {
            throw Exception("Stream extractor API error for $videoId: ${video.error}")
        }

        val allStreams = video.adaptiveFormats + video.formatStreams
        val audioStream = allStreams.firstOrNull { it.itag == "140" }
            ?: allStreams.firstOrNull { it.type?.startsWith("audio/") == true }
            ?: throw Exception("No audio stream for $videoId")

        return audioStream.url
            ?: throw Exception("Audio stream for $videoId has no URL")
    }
}

internal fun buildYoutubeMusicSearchRequestBody(
    query: String,
    params: String,
    clientVersionDate: String = YTM_CLIENT_VERSION_DATE
): String = sharedJson.encodeToString(
    YoutubeMusicSearchRequest(
        context = YoutubeMusicContext(
            client = YoutubeMusicClient(
                clientName = "WEB_REMIX",
                clientVersion = "1.$clientVersionDate.01.00",
                hl = "en",
                gl = "US"
            ),
            user = emptyMap()
        ),
        query = query,
        params = params
    )
)

internal fun parseYoutubeMusicSearchResults(root: JsonElement): List<YoutubeMusicSearchResult> =
    root.findObjects("musicResponsiveListItemRenderer")
        .mapNotNull { parseMusicResponsiveListItem(it) }
        .distinctBy { it.videoId }

private fun parseMusicResponsiveListItem(item: JsonObject): YoutubeMusicSearchResult? {
    val videoId = item.findString("videoId") ?: return null
    val title = item["flexColumns"]
        ?.jsonArrayOrNull
        ?.firstOrNull()
        ?.jsonObjectOrNull
        ?.get("musicResponsiveListItemFlexColumnRenderer")
        ?.jsonObjectOrNull
        ?.get("text")
        ?.jsonObjectOrNull
        ?.get("runs")
        ?.jsonArrayOrNull
        ?.firstOrNull()
        ?.jsonObjectOrNull
        ?.get("text")
        ?.jsonPrimitiveOrNull
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val videoType = item.findString("musicVideoType")
    val detailRuns = item["flexColumns"]
        ?.jsonArrayOrNull
        ?.drop(1)
        ?.flatMap { column ->
            column.jsonObjectOrNull
                ?.get("musicResponsiveListItemFlexColumnRenderer")
                ?.jsonObjectOrNull
                ?.get("text")
                ?.jsonObjectOrNull
                ?.get("runs")
                ?.jsonArrayOrNull
                ?.mapNotNull { it.jsonObjectOrNull }
                ?: emptyList()
        }
        ?: emptyList()

    val parts = detailRuns
        .mapNotNull { it["text"]?.jsonPrimitiveOrNull?.contentOrNull?.trim() }
        .filter { it.isNotBlank() && it != "•" }

    val durationText = parts.lastOrNull { it.isDurationText() }
    val durationMs = durationText?.toDurationMs() ?: 0L
    val artists = detailRuns
        .filter { run ->
            val browseId = run.findString("browseId")
            browseId?.startsWith("UC") == true || browseId?.startsWith("MPLA") == true
        }
        .mapNotNull { it["text"]?.jsonPrimitiveOrNull?.contentOrNull?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty {
            parts
                .filterNot { it.equals("Song", ignoreCase = true) || it.equals("Video", ignoreCase = true) }
                .filterNot { it.isDurationText() }
                .take(1)
        }

    val albumTitle = parts
        .filterNot { it.equals("Song", ignoreCase = true) || it.equals("Video", ignoreCase = true) }
        .filterNot { it.isDurationText() }
        .drop(artists.size)
        .firstOrNull()

    return YoutubeMusicSearchResult(
        videoId = videoId,
        title = title,
        artists = artists,
        albumTitle = albumTitle,
        durationMs = durationMs,
        videoType = videoType
    )
}

private fun JsonElement.findObjects(key: String): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                element[key]?.jsonObjectOrNull?.let { found.add(it) }
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }
    visit(this)
    return found
}

private fun JsonElement.findString(key: String): String? {
    when (this) {
        is JsonObject -> {
            this[key]?.jsonPrimitiveOrNull?.contentOrNull?.let { return it }
            values.forEach { child -> child.findString(key)?.let { return it } }
        }
        is JsonArray -> forEach { child -> child.findString(key)?.let { return it } }
        else -> Unit
    }
    return null
}

private fun String.isDurationText(): Boolean =
    matches(Regex("""\d{1,2}:\d{2}(?::\d{2})?"""))

private fun String.toDurationMs(): Long {
    val parts = split(":").mapNotNull { it.toLongOrNull() }
    if (parts.isEmpty()) return 0L
    val seconds = parts.fold(0L) { acc, part -> acc * 60 + part }
    return seconds * 1000
}

private val JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

private val JsonElement.jsonArrayOrNull: JsonArray?
    get() = this as? JsonArray

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
