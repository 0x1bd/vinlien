package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class AdminStats(
    val totalUsers: Int,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val totalPlaytimeMs: Long,
    val topUsers: List<UserStat>,
    val topTracks: List<TrackStat>,
    val topArtists: List<UserStat>,
    val playsLast7Days: List<DayStat>,
    val peakHour: Int,
    val avgPlaysPerUser: Double
)