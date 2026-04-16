package org.kvxd.vinlien.shared.models.admin

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.auth.User

@Serializable
data class AdminStatsResponse(
    val stats: AdminStats,
    val pending: List<User>
)