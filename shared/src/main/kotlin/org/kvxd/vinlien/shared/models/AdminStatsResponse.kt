package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class AdminStatsResponse(
    val stats: AdminStats,
    val pending: List<User>
)