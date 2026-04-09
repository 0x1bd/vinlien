package org.kvxd.vinlien.backends.musicbrainz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder

private val MB_HEADERS = mapOf("User-Agent" to "Vinlien/1.0 ( https://github.com )")

@Serializable
private data class MbRecordingSearch(val recordings: List<MbRecording> = emptyList())

@Serializable
private data class MbRecording(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbRelease> = emptyList()
) {
    fun toTrack(): Track? {
        val trackTitle = title ?: return null
        val artist = artistCredit.joinToString("") { (it.name ?: "") + (it.joinphrase ?: "") }.trim().ifEmpty { "Unknown" }
        val trackId = id ?: return null
        val releaseId = releases.firstOrNull()?.id
        val artwork = if (releaseId != null) "https://coverartarchive.org/release/$releaseId/front"
                      else "https://ui-avatars.com/api/?name=${URLEncoder.encode(artist, "UTF-8")}&background=random"
        return Track(
            id = "mb:$trackId",
            title = trackTitle,
            artist = artist,
            durationMs = length ?: 0L,
            artworkUrl = artwork,
            canonicalId = "${artist.lowercase().trim()}:::${trackTitle.lowercase().trim()}"
        )
    }
}

@Serializable
private data class MbArtistCredit(val name: String? = null, val joinphrase: String? = null)

@Serializable
private data class MbRelease(val id: String? = null)

@Serializable
private data class MbReleaseSearch(val releases: List<MbReleaseItem> = emptyList())

@Serializable
private data class MbReleaseItem(
    val id: String? = null,
    val title: String? = null,
    val date: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList()
)

@Serializable
private data class MbReleaseDetails(
    val id: String? = null,
    val title: String? = null,
    val date: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val media: List<MbMedia> = emptyList()
)

@Serializable
private data class MbMedia(val tracks: List<MbTrack> = emptyList())

@Serializable
private data class MbTrack(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    val recording: MbRecordingRef? = null
)

@Serializable
private data class MbRecordingRef(val id: String? = null)


class MusicBrainzMetadataProvider : MusicProvider {
    override val id = "mb"
    override val name = "MusicBrainz"
    override val capabilities = setOf(Capability.TRACK_SEARCH, Capability.ALBUM_TRACKS)

    override val timeoutMs = 8_000L

    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val res = sharedJson.decodeFromString<MbRecordingSearch>(
                fetch("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=15", MB_HEADERS)
            )
            res.recordings.mapNotNull { it.toTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAlbum(artist: String, albumTitle: String): Album? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("""artist:"$artist" AND release:"$albumTitle"""", "UTF-8")
            val searchRes = sharedJson.decodeFromString<MbReleaseSearch>(
                fetch("https://musicbrainz.org/ws/2/release/?query=$query&fmt=json&limit=3", MB_HEADERS)
            )
            val releaseId = searchRes.releases.firstOrNull { r ->
                r.title?.equals(albumTitle, ignoreCase = true) == true
            }?.id ?: return@withContext null

            val details = sharedJson.decodeFromString<MbReleaseDetails>(
                fetch("https://musicbrainz.org/ws/2/release/$releaseId?inc=recordings&fmt=json", MB_HEADERS)
            )

            val artistName = details.artistCredit.joinToString("") { (it.name ?: "") + (it.joinphrase ?: "") }
                .trim().ifEmpty { artist }
            val title = details.title ?: albumTitle
            val year = details.date?.take(4)?.toIntOrNull()
            val artworkUrl = "https://coverartarchive.org/release/$releaseId/front"

            val tracks = details.media.flatMap { it.tracks }.mapNotNull { t ->
                val trackTitle = t.title ?: return@mapNotNull null
                val recordingId = t.recording?.id ?: t.id ?: return@mapNotNull null
                Track(
                    id = "mb:$recordingId",
                    title = trackTitle,
                    artist = artistName,
                    durationMs = t.length ?: 0L,
                    artworkUrl = artworkUrl,
                    canonicalId = "${artistName.lowercase().trim()}:::${trackTitle.lowercase().trim()}"
                )
            }

            Album(
                id = "mb:album:${artistName}:::${title}",
                title = title,
                artist = artistName,
                artworkUrl = artworkUrl,
                year = year,
                tracks = tracks
            )
        } catch (e: Exception) {
            null
        }
    }
}
