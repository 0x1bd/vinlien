package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class AdminStats(
    val totalUsers: Int,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val totalPlaytimeMs: Long,
    val topUsers: List<UserStat>
)