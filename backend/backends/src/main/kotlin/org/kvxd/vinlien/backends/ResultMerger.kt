package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.models.media.Album
import org.kvxd.vinlien.shared.models.media.ArtistInfo
import org.kvxd.vinlien.shared.models.media.Track

private fun String.primaryArtistPart(): String =
    split(Regex("""[\s]*[&,][\s]*|[\s]+(?<![a-zA-Z])(feat|ft|featuring)\.?\s+""", RegexOption.IGNORE_CASE))
        .firstOrNull()?.normalized() ?: normalized()

object TrackMerger {
    private fun Track.fingerprint() = "${artist.primaryArtistPart()}:::${title.normalized()}"

    fun merge(tracks: List<Track>): List<Track> =
        tracks.groupBy { it.fingerprint() }
            .map { (_, group) -> mergeGroup(group) }

    private fun mergeGroup(group: List<Track>): Track {
        val primary = group.maxByOrNull { it.artists.size } ?: group.first()
        val mergedArtists = group.flatMap { it.artists }.distinctBy { it.normalized() }.ifEmpty { primary.artists }
        return primary.copy(
            artworkUrl = group.firstNotNullOfOrNull { it.artworkUrl } ?: primary.artworkUrl,
            durationMs = group.firstOrNull { it.durationMs > 0 }?.durationMs ?: primary.durationMs,
            artist = mergedArtists.joinToString(", "),
            artists = mergedArtists,
            lastFmUrl = group.firstNotNullOfOrNull { it.lastFmUrl } ?: primary.lastFmUrl,
            albumTitle = group.firstNotNullOfOrNull { it.albumTitle } ?: primary.albumTitle,
            albumId = group.firstNotNullOfOrNull { it.albumId } ?: primary.albumId,
            popularityScore = group.mapNotNull { it.popularityScore }.maxOrNull() ?: primary.popularityScore
        )
    }
}

object AlbumMerger {
    private fun Album.fingerprint() = "${artist.primaryArtistPart()}:::${title.normalized()}"

    fun dedup(albums: List<Album>): List<Album> =
        albums.groupBy { it.fingerprint() }.map { (_, group) -> mergeGroup(group) }

    fun mergeOne(albums: List<Album>, nativeId: String): Album? {
        if (albums.isEmpty()) return null

        val (canonicalArtist, canonicalTitle) = parseNativeId(nativeId) ?: (null to null)

        val matching = if (canonicalTitle != null) {
            albums.filter { it.title.normalized() == canonicalTitle.normalized() }
        } else albums

        val candidates = matching.ifEmpty { albums }

        val originPrefix = when {
            nativeId.startsWith("deezer:album:") -> "deezer:"
            nativeId.startsWith("lastfm:album:") -> "lastfm:"
            nativeId.startsWith("itunes:album:") -> "itunes:"
            nativeId.startsWith("mb:album:") -> "mb:"
            else -> null
        }
        val base = (if (originPrefix != null) candidates.firstOrNull { it.id.startsWith(originPrefix) } else null)
            ?: candidates.maxByOrNull { it.tracks.distinctBy { t -> t.title.normalized() }.size }
            ?: albums.first()

        val cleanArtist = canonicalArtist
            ?: candidates.minByOrNull { it.artist.length }?.artist
            ?: base.artist

        val allTracks = albums.flatMap { it.tracks }

        val durationByTitle: Map<String, Long> = allTracks
            .filter { it.durationMs > 0 }
            .associateBy({ it.title.normalized() }, { it.durationMs })

        val artworkByTitle: Map<String, String> = allTracks
            .filter { it.artworkUrl != null }
            .associate { it.title.normalized() to it.artworkUrl!! }

        val albumArtwork = albums.mapNotNull { it.artworkUrl }.firstOrNull() ?: base.artworkUrl

        val resolvedTitle = canonicalTitle ?: base.title

        val mergedTracks = base.tracks
            .distinctBy { it.title.normalized() }
            .map { track ->
                val key = track.title.normalized()
                track.copy(
                    artworkUrl = track.artworkUrl ?: artworkByTitle[key] ?: albumArtwork,
                    durationMs = if (track.durationMs > 0) track.durationMs else durationByTitle[key] ?: 0L,
                    albumTitle = track.albumTitle ?: resolvedTitle,
                    albumId = track.albumId ?: nativeId
                )
            }

        return base.copy(
            id = nativeId,
            artist = cleanArtist,
            artworkUrl = albumArtwork,
            year = albums.mapNotNull { it.year }.firstOrNull() ?: base.year,
            tracks = mergedTracks
        )
    }

    fun parseNativeId(nativeId: String): Pair<String, String>? = when {
        nativeId.startsWith("merged:album:") -> {
            val parts = nativeId.removePrefix("merged:album:").split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        nativeId.startsWith("lastfm:album:") -> {
            val parts = nativeId.removePrefix("lastfm:album:").split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        nativeId.startsWith("itunes:album:") -> {
            val parts = nativeId.removePrefix("itunes:album:").split(":::", limit = 3)
            if (parts.size == 3) parts[1] to parts[2] else null
        }

        nativeId.startsWith("mb:album:") -> {
            val parts = nativeId.removePrefix("mb:album:").split(":::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }

        nativeId.startsWith("deezer:album:") -> {
            val parts = nativeId.removePrefix("deezer:album:").split(":::", limit = 3)
            if (parts.size == 3) parts[1] to parts[2] else null
        }

        else -> null
    }

    private fun mergeGroup(group: List<Album>): Album {
        val base = group.maxByOrNull { it.tracks.size } ?: group.first()
        val cleanArtist = group.minByOrNull { it.artist.length }?.artist ?: base.artist
        return base.copy(
            artist = cleanArtist,
            artworkUrl = group.mapNotNull { it.artworkUrl }.firstOrNull() ?: base.artworkUrl,
            year = group.mapNotNull { it.year }.firstOrNull() ?: base.year
        )
    }
}

object ArtistInfoMerger {
    fun mergeOne(infos: List<ArtistInfo>): ArtistInfo? {
        if (infos.isEmpty()) return null
        val name = infos.first().name
        val bio = infos.maxByOrNull { it.bio.length }?.bio ?: ""
        val imageUrl = infos.mapNotNull { it.imageUrl }.firstOrNull()
        val tags = infos.flatMap { it.tags }.distinct().take(5)
        return ArtistInfo(name, bio, tags, imageUrl)
    }
}
