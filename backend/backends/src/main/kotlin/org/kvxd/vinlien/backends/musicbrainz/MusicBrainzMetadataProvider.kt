package org.kvxd.vinlien.backends.musicbrainz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.backends.MetadataProvider
import org.kvxd.vinlien.backends.fetch
import org.kvxd.vinlien.backends.sharedJson
import org.kvxd.vinlien.shared.Track
import java.net.URLEncoder

@Serializable
private data class MbResponse(
    val recordings: List<MbRecording> = emptyList()
)

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
        val artist = artistCredit.firstOrNull()?.name ?: "Unknown"
        val trackId = id ?: return null

        val releaseId = releases.firstOrNull()?.id
        val artwork = if (releaseId != null) {
            "https://coverartarchive.org/release/$releaseId/front"
        } else {
            "https://ui-avatars.com/api/?name=${URLEncoder.encode(artist, "UTF-8")}&background=random"
        }

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
private data class MbArtistCredit(val name: String? = null)

@Serializable
private data class MbRelease(val id: String? = null)


class MusicBrainzMetadataProvider : MetadataProvider {
    override val name = "MusicBrainz"

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val jsonString = fetch(
                "https://musicbrainz.org/ws/2/recording/?query=$encodedQuery&fmt=json&limit=15",
                mapOf("User-Agent" to "Vinlien/1.0 ( https://github.com )")
            )
            val res = sharedJson.decodeFromString<MbResponse>(jsonString)
            res.recordings.mapNotNull { it.toTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getRecommendations(track: Track): List<Track> = emptyList()
    override suspend fun getTrending(): List<Track> = emptyList()
}