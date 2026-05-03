package org.kvxd.vinlien.server.services

import org.kvxd.vinlien.server.TrackFingerprint
import org.kvxd.vinlien.server.db.repositories.HistoryRepository
import org.kvxd.vinlien.shared.models.feed.HomeFeed
import org.kvxd.vinlien.shared.models.media.Track

object HomeFeedService {

    suspend fun buildFeed(userId: String): HomeFeed {
        val parsedTracks = HistoryRepository.getRecentHistory(userId, limit = 200).map { it.first }

        val tracksBySong = parsedTracks.groupBy { it.songKey() }
        val representativeTracks = tracksBySong.mapValues { (_, tracks) -> tracks.bestRepresentative() }

        val recentlyPlayed = parsedTracks
            .distinctBy { it.songKey() }
            .take(10)

        val listenAgain = tracksBySong
            .filter { it.value.size >= 2 }
            .keys
            .shuffled()
            .mapNotNull { representativeTracks[it] }
            .take(10)

        val recentSongKeys = recentlyPlayed.take(15).map { it.songKey() }.toSet()
        val forgottenFavorites = tracksBySong
            .filter { it.value.size >= 2 && it.key !in recentSongKeys }
            .entries.sortedByDescending { it.value.size }
            .mapNotNull { representativeTracks[it.key] }
            .take(10)
        val artists = parsedTracks.flatMap { it.artists }.distinct().shuffled().take(3)

        return HomeFeed(recentlyPlayed, listenAgain, forgottenFavorites, artists)
    }

    private fun Track.songKey(): String {
        canonicalId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { return it }
        val artistKey = TrackFingerprint.of(artist)
        val titleKey = TrackFingerprint.of(title)
        return "$artistKey:::$titleKey"
    }

    private fun List<Track>.bestRepresentative(): Track =
        maxWithOrNull(
            compareBy<Track> {
                val artworkUrl = it.artworkUrl
                if (artworkUrl != null && !artworkUrl.contains("ytimg.com")) 1 else 0
            }.thenBy { if (it.durationMs > 0) 1 else 0 }
                .thenBy { if (it.lastFmUrl != null) 1 else 0 }
                .thenBy { it.artists.size }
        ) ?: first()
}
