package org.kvxd.vinlien.backends

import org.kvxd.vinlien.shared.Album
import org.kvxd.vinlien.shared.ArtistInfo
import org.kvxd.vinlien.shared.Track

private fun String.primaryArtistPart(): String =
    split(Regex("""[\s]*[&,][\s]*|[\s]+(feat|ft|featuring)\.?\s+""", RegexOption.IGNORE_CASE))
        .firstOrNull()?.normalized() ?: normalized()

object TrackMerger {
    private fun Track.fingerprint() = "${artist.primaryArtistPart()}:::${title.normalized()}"

    fun merge(tracks: List<Track>): List<Track> =
        tracks.groupBy { it.fingerprint() }
            .map { (_, group) -> mergeGroup(group) }
            .sortedByDescending { if (it.artworkUrl != null) 1 else 0 }

    private fun mergeGroup(group: List<Track>): Track {
        val primary = group.maxByOrNull { it.artists.size } ?: group.first()
        val mergedArtists = group.flatMap { it.artists }.distinctBy { it.normalized() }.ifEmpty { primary.artists }
        return primary.copy(
            artworkUrl = group.mapNotNull { it.artworkUrl }.firstOrNull() ?: primary.artworkUrl,
            durationMs = group.firstOrNull { it.durationMs > 0 }?.durationMs ?: primary.durationMs,
            artist = mergedArtists.joinToString(", "),
            artists = mergedArtists,
            lastFmUrl = group.mapNotNull { it.lastFmUrl }.firstOrNull() ?: primary.lastFmUrl
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

        val base = (matching.ifEmpty { albums }).maxByOrNull { it.tracks.size } ?: albums.first()

        val cleanArtist = canonicalArtist
            ?: (matching.ifEmpty { albums }).minByOrNull { it.artist.length }?.artist
            ?: base.artist

        val otherArtworkByTitle: Map<String, String> = albums
            .filter { it !== base }
            .flatMap { it.tracks }
            .filter { it.artworkUrl != null }
            .associate { it.title.normalized() to it.artworkUrl!! }

        val albumArtwork = albums.mapNotNull { it.artworkUrl }.firstOrNull() ?: base.artworkUrl

        val mergedTracks = base.tracks.map { track ->
            when {
                track.artworkUrl != null -> track
                else -> track.copy(artworkUrl = otherArtworkByTitle[track.title.normalized()] ?: albumArtwork)
            }
        }

        return base.copy(
            id = nativeId,
            artist = cleanArtist,
            artworkUrl = albumArtwork,
            year = albums.mapNotNull { it.year }.firstOrNull() ?: base.year,
            tracks = mergedTracks
        )
    }

    private fun parseNativeId(nativeId: String): Pair<String, String>? = when {
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
