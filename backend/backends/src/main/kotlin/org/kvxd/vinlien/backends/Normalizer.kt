package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.Track

private val ARTIST_SPLIT = Regex(
    """[\s]*[&,][\s]*|[\s]+(feat|ft|featuring)\.?\s+""",
    RegexOption.IGNORE_CASE
)

object Normalizer {

    /**
     * Extract the primary/lead artist from a track.
     * Prefers [Track.artists] (already split), falls back to splitting [Track.artist] on
     * "&", ",", "feat", etc. Never exposes the full concatenated string to API calls.
     */
    fun primaryArtist(track: Track): String =
        track.artists.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: track.artist.split(ARTIST_SPLIT).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: track.artist


    private val FEAT_IN_TITLE = Regex(
        """[\s\-]*[\[(]?\s*(?:feat|ft|featuring)\.?\s+([^()\[\]\r\n]+?)[\])]?\s*$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TITLE_NOISE = Regex(
        """\s*[\[(]\s*(?:official\s+(?:video|audio|music\s+video|lyric\s+video)|music\s+video|lyric\s+video|official\s+audio|audio|hd|hq|4k)\s*[\])]""",
        RegexOption.IGNORE_CASE
    )

    fun track(track: Track): Track {
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

        val artists = splitArtists(artist)

        return track.copy(title = title, artist = artist, artists = artists)
    }

    private fun splitArtists(artist: String): List<String> =
        artist.split(Regex("""[\s,]*(?:feat|ft|featuring)\.?\s+|[\s]*&[\s]*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun album(album: Album): Album {
        val title = normalizeCaps(album.title.trim())
        val artist = normalizeCaps(album.artist.trim())
        val tracks = album.tracks.map { track(it) }
        return album.copy(title = title, artist = artist, tracks = tracks)
    }

    private fun normalizeCaps(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 2 || letters != letters.uppercase()) return s
        return s.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word[0].uppercaseChar() + word.drop(1).lowercase()
        }
    }
}
