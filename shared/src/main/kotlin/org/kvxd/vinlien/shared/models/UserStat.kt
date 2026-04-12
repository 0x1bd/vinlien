package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class UserStat(val username: String, val playCount: Int)