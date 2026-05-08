package org.kvxd.vinlien.server.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.kvxd.vinlien.server.CacheManager
import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.server.db.repositories.PreferenceImportRepository
import org.kvxd.vinlien.shared.models.media.Track
import org.kvxd.vinlien.shared.models.preferences.PreferenceImportRequest
import org.kvxd.vinlien.shared.models.preferences.PreferenceImportResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object PreferenceImportService {
    private const val MAX_IMPORT_PLAYS = 750
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importPreferences(userId: String, req: PreferenceImportRequest): PreferenceImportResponse {
        val parsed = parsePreferences(req.fileName, req.content)

        val plays = parsed.signals.map { signal ->
            PreferenceImportRepository.ImportPlay(
                track = signal.toTrack(),
                playedMs = signal.playedMs,
                durationMs = signal.durationMs,
                timestampMs = signal.timestampMs,
                source = signal.source
            )
        }

        var importedCount = 0
        if (plays.isNotEmpty()) {
            importedCount = PreferenceImportRepository.insertImportedPlays(userId, plays)
        }
        if (importedCount > 0) {
            CacheManager.homeFeed.remove(userId)
            RecService.invalidate(userId)
        }

        return parsed.toResponse(importedCount)
    }

    internal fun previewPreferences(fileName: String, content: String): PreferenceImportResponse =
        parsePreferences(fileName, content).toResponse()

    private fun parsePreferences(fileName: String, content: String): ParsedImport {
        val normalizedFileName = fileName.trim().ifBlank { "preferences" }
        val rawSignals = parse(normalizedFileName, content)
        val latestByTrack = rawSignals
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .groupBy { "${it.artist.lowercase().trim()}|${TrackFingerprint.of(it.title)}" }
            .mapNotNull { (_, grouped) -> grouped.maxByOrNull { it.timestampMs } }
            .sortedByDescending { it.score }
            .take(MAX_IMPORT_PLAYS)

        return ParsedImport(
            fileName = normalizedFileName,
            content = content,
            rawCount = rawSignals.size,
            signals = latestByTrack
        )
    }

    private fun ParsedImport.toResponse(importedCount: Int = signals.size): PreferenceImportResponse {
        val source = signals.firstOrNull()?.source ?: detectSource(fileName, content)
        val skipped = (rawCount - importedCount).coerceAtLeast(0)
        return PreferenceImportResponse(
            source = source,
            imported = importedCount,
            skipped = skipped,
            message = when {
                importedCount <= 0 -> "No new playable preference entries were found."
                skipped > 0 -> "Imported $importedCount preference signals and skipped $skipped duplicates or incomplete entries."
                else -> "Imported $importedCount preference signals."
            }
        )
    }

    private fun parse(fileName: String, content: String): List<ImportSignal> {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return emptyList()

        if (fileName.endsWith(".csv", ignoreCase = true)) {
            return parseCsv(fileName, trimmed)
        }

        return runCatching { parseJson(fileName, trimmed) }
            .getOrElse { parseCsv(fileName, trimmed) }
    }

    private fun parseJson(fileName: String, content: String): List<ImportSignal> {
        val root = json.parseToJsonElement(content)
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"]?.jsonArrayOrNull()
                ?: root["entries"]?.jsonArrayOrNull()
                ?: root["history"]?.jsonArrayOrNull()
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        return entries.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            parseSpotifyObject(fileName, obj) ?: parseYoutubeObject(fileName, obj) ?: parseGenericObject(fileName, obj)
        }
    }

    private fun parseSpotifyObject(fileName: String, obj: JsonObject): ImportSignal? {
        val title = obj.string("master_metadata_track_name")
            ?: obj.string("trackName")
            ?: obj.string("track_name")
            ?: return null
        val artist = obj.string("master_metadata_album_artist_name")
            ?: obj.string("artistName")
            ?: obj.string("artist_name")
            ?: return null
        val album = obj.string("master_metadata_album_album_name") ?: obj.string("albumName") ?: obj.string("album")
        val uri = obj.string("spotify_track_uri")
        val playedMs = obj.long("ms_played") ?: obj.long("msPlayed") ?: 0L
        val timestamp = parseTimestamp(obj.string("ts") ?: obj.string("endTime")) ?: System.currentTimeMillis()
        val durationMs = obj.long("duration_ms") ?: 0L
        val score = playedMs.toDouble().coerceAtLeast(1.0)

        return ImportSignal(
            title = title.cleanTitle(),
            artist = artist.cleanArtist(),
            album = album?.cleanTitle(),
            externalId = uri?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
            source = "spotify",
            playedMs = playedMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            timestampMs = timestamp,
            score = score + fileName.scoreHint("spotify")
        )
    }

    private fun parseYoutubeObject(fileName: String, obj: JsonObject): ImportSignal? {
        val rawTitle = obj.string("title") ?: obj.string("name") ?: return null
        val title = rawTitle
            .removePrefix("Watched ")
            .removePrefix("Listened to ")
            .trim()
            .trim('"')
        if (title.isBlank() || title.equals("a video that has been removed", ignoreCase = true)) return null

        val artist = obj["subtitles"]
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?.string("name")
            ?.cleanArtist()
            ?: obj.string("channelTitle")
            ?: obj.string("artist")
            ?: return null

        val url = obj.string("titleUrl") ?: obj.string("url")
        val videoId = url?.substringAfter("v=", "")?.substringBefore("&")?.takeIf { it.isNotBlank() }
        val timestamp = parseTimestamp(obj.string("time")) ?: System.currentTimeMillis()

        return ImportSignal(
            title = title.cleanTitle(),
            artist = artist.cleanArtist(),
            album = null,
            externalId = videoId,
            source = "youtube",
            playedMs = obj.long("ms_played") ?: obj.long("watchTimeMs") ?: 180_000L,
            durationMs = obj.long("duration_ms") ?: 0L,
            timestampMs = timestamp,
            score = 180_000.0 + fileName.scoreHint("youtube")
        )
    }

    private fun parseGenericObject(fileName: String, obj: JsonObject): ImportSignal? {
        val title = obj.string("title") ?: obj.string("track") ?: obj.string("song") ?: return null
        val artist = obj.string("artist") ?: obj.string("artistName") ?: obj.string("creator") ?: return null
        val source = detectSource(fileName, obj.toString())
        return ImportSignal(
            title = title.cleanTitle(),
            artist = artist.cleanArtist(),
            album = obj.string("album") ?: obj.string("albumTitle"),
            externalId = obj.string("id") ?: obj.string("trackId"),
            source = source,
            playedMs = obj.long("playedMs") ?: obj.long("msPlayed") ?: 180_000L,
            durationMs = obj.long("durationMs") ?: obj.long("duration_ms") ?: 0L,
            timestampMs = parseTimestamp(obj.string("playedAt") ?: obj.string("timestamp") ?: obj.string("time"))
                ?: System.currentTimeMillis(),
            score = (obj.double("weight") ?: 1.0) * 180_000.0
        )
    }

    private fun parseCsv(fileName: String, content: String): List<ImportSignal> {
        val rows = content.lineSequence()
            .filter { it.isNotBlank() }
            .map(::parseCsvLine)
            .toList()
        if (rows.size < 2) return emptyList()

        val headers = rows.first().map { it.trim().lowercase() }
        fun indexOf(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
            headers.indexOf(name).takeIf { it >= 0 }
        } ?: -1
        fun firstPrefixIndex(vararg prefixes: String): Int =
            headers.indexOfFirst { header -> prefixes.any { prefix -> header.startsWith(prefix) } }

        val titleIndex = indexOf("title", "track", "track name", "song", "song title")
        val artistIndex = indexOf("artist", "artist name", "album artist", "channel").takeIf { it >= 0 }
            ?: firstPrefixIndex("artist name ")
        if (titleIndex < 0 || artistIndex < 0) return emptyList()

        val albumIndex = indexOf("album", "album name")
        val playedMsIndex = indexOf("ms played", "ms_played", "msplayed", "playedms")
        val durationIndex = indexOf("duration ms", "duration_ms", "durationms")
        val timestampIndex = indexOf("played at", "played_at", "timestamp", "time", "endtime", "end time")
        val idIndex = indexOf("id", "track id", "spotify uri", "url", "video id")
        val source = detectSource(fileName, content)

        return rows.drop(1).mapNotNull { row ->
            val title = row.getOrNull(titleIndex)?.cleanTitle().orEmpty()
            val artist = collectArtistNames(headers, row, artistIndex)
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val playedMs = row.getOrNull(playedMsIndex)?.toLongOrNull() ?: 180_000L
            ImportSignal(
                title = title,
                artist = artist,
                album = row.getOrNull(albumIndex)?.cleanTitle(),
                externalId = row.getOrNull(idIndex)?.takeIf { it.isNotBlank() },
                source = source,
                playedMs = playedMs,
                durationMs = row.getOrNull(durationIndex)?.toLongOrNull() ?: 0L,
                timestampMs = parseTimestamp(row.getOrNull(timestampIndex)) ?: System.currentTimeMillis(),
                score = playedMs.toDouble().coerceAtLeast(1.0)
            )
        }
    }

    private fun collectArtistNames(headers: List<String>, row: List<String>, fallbackIndex: Int): String {
        val numberedArtists = headers.mapIndexedNotNull { index, header ->
            row.getOrNull(index)
                ?.takeIf { header.startsWith("artist name ") && it.isNotBlank() }
                ?.cleanArtist()
        }
        if (numberedArtists.isNotEmpty()) return numberedArtists.joinToString(", ")
        return row.getOrNull(fallbackIndex)?.cleanArtist().orEmpty()
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    private fun detectSource(fileName: String, content: String): String {
        val haystack = "$fileName\n$content".lowercase(Locale.ROOT)
        return when {
            "spotify" in haystack || "spotify_track_uri" in haystack -> "spotify"
            "youtube" in haystack || "youtu.be" in haystack || "youtube.com" in haystack -> "youtube"
            else -> "import"
        }
    }

    private fun parseTimestamp(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        text.toLongOrNull()?.let { raw ->
            return if (raw < 10_000_000_000L) raw * 1000L else raw
        }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { primitive ->
            primitive.contentOrNull?.toLongOrNull() ?: primitive.doubleOrNull?.toLong()
        }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun String.cleanTitle(): String = trim()
        .replace(Regex("""\s+"""), " ")
        .take(255)

    private fun String.cleanArtist(): String = cleanTitle()
        .removeSuffix(" - Topic")
        .trim()

    private fun String.scoreHint(source: String): Double =
        if (contains(source, ignoreCase = true)) 10_000.0 else 0.0

    private data class ImportSignal(
        val title: String,
        val artist: String,
        val album: String?,
        val externalId: String?,
        val source: String,
        val playedMs: Long,
        val durationMs: Long,
        val timestampMs: Long,
        val score: Double
    ) {
        fun toTrack(): Track {
            val baseId = externalId?.takeIf { it.isNotBlank() } ?: stableHash("$source|$artist|$title")
            return Track(
                id = "import:$source:$baseId",
                title = title,
                artist = artist,
                artists = listOf(artist),
                durationMs = durationMs,
                canonicalId = "import:${stableHash("${artist.lowercase()}|${title.lowercase()}")}",
                albumTitle = album
            )
        }
    }

    private data class ParsedImport(
        val fileName: String,
        val content: String,
        val rawCount: Int,
        val signals: List<ImportSignal>
    )

    private fun stableHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(20)
    }
}