package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.models.Album
import org.kvxd.vinlien.shared.models.Track

private val PRIMARY_ARTIST_SPLIT = Regex(
    """[\s]*[&,][\s]*|[\s]+(feat|ft|featuring)\.?\s+""",
    RegexOption.IGNORE_CASE
)

private val ARTIST_COLLABORATION_SPLIT = Regex(
    """[\s]*(?:feat|ft|featuring)\.?\s+|[\s]*[,&][\s]*""",
    RegexOption.IGNORE_CASE
)

private val FEAT_IN_TITLE = Regex(
    """[\s\-]*[\[(]?\s*(?:feat|ft|featuring)\.?\s+([^()\[\]\r\n]+?)[\])]?\s*$""",
    setOf(RegexOption.IGNORE_CASE)
)

private val TITLE_NOISE = Regex(
    """\s*[\[(]\s*(?:(?:official|music|lyric(?:s)?|audio|video|hd|hq|4k|clip|vevo)(?:\s+(?:official|music|lyric(?:s)?|audio|video|hd|hq|4k|clip|vevo))*)\s*[\])]""",
    RegexOption.IGNORE_CASE
)

internal fun String.normalized() =
    lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().replace(Regex("\\s+"), " ")

internal fun String.withoutSpaces() = replace(" ", "")

internal fun String.withoutFeaturedArtists() =
    replace(Regex("""\s*(feat|ft|featuring)\.?\s+.*""", RegexOption.IGNORE_CASE), "").trim()

internal fun normalizeArtistName(artist: String) = artist.normalized().withoutFeaturedArtists()

object Normalizer {

    fun canonicalIdFor(artist: String, title: String): String =
        "${artist.lowercase().trim()}:::${title.lowercase().trim()}"

    fun primaryArtist(track: Track): String =
        track.artists.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: track.artist.split(PRIMARY_ARTIST_SPLIT).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: track.artist

    fun normalizeTrack(track: Track): Track {
        var title = track.title.trim()
        var artist = track.artist.trim()

        val featMatch = FEAT_IN_TITLE.find(title)
        if (featMatch != null) {
            val featArtists = featMatch.groupValues[1].trim().trimEnd(')')
            title = title.removeRange(featMatch.range).trim().trimEnd('-', ' ')
            if (!artist.contains(featArtists, ignoreCase = true)) {
                artist = "$artist & $featArtists"
            }
        }

        title = title.replace(TITLE_NOISE, "").trim()
        title = normalizeCaps(title)
        artist = normalizeCaps(artist)

        return track.copy(title = title, artist = artist, artists = splitArtists(artist))
    }

    fun normalizeAlbum(album: Album): Album {
        val title = normalizeCaps(album.title.trim())
        val artist = normalizeCaps(album.artist.trim())
        val tracks = album.tracks.map { normalizeTrack(it) }
        return album.copy(title = title, artist = artist, tracks = tracks)
    }

    private fun splitArtists(artist: String): List<String> =
        artist.split(ARTIST_COLLABORATION_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalizeCaps(s: String): String {
        val letters = s.filter { it.isLetter() }
        val isAllUppercase = letters.length >= 2 && letters == letters.uppercase()
        if (!isAllUppercase) return s
        return s.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word[0].uppercaseChar() + word.drop(1).lowercase()
        }
    }
}
