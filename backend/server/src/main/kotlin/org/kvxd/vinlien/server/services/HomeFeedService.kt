package org.kvxd.vinlien.server.services

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.server.History
import org.kvxd.vinlien.server.Tracks
import org.kvxd.vinlien.shared.models.feed.HomeFeed

object HomeFeedService {

    suspend fun buildFeed(userId: String): HomeFeed = dbQuery {
        val parsedTracks = (History innerJoin Tracks)
            .selectAll()
            .where { History.userId eq userId }
            .orderBy(History.timestamp to SortOrder.DESC)
            .limit(200)
            .map { it.toTrack() }

        val recentlyPlayed = parsedTracks.distinctBy { it.canonicalId ?: it.id }.take(10)
        val trackCounts = parsedTracks.groupingBy { it }.eachCount()
        val listenAgain = trackCounts.filter { it.value >= 2 }.keys.toList().shuffled().take(10)
        val recentIds = recentlyPlayed.take(15).map { it.id }
        val forgottenFavorites = trackCounts
            .filter { it.value >= 2 && it.key.id !in recentIds }
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(10)
        val artists = parsedTracks.flatMap { it.artists }.distinct().shuffled().take(3)

        HomeFeed(recentlyPlayed, listenAgain, forgottenFavorites, artists)
    }
}
