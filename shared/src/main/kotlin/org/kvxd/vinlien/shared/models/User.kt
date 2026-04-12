package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val role: String,
    val token: String? = null,
    val requiresPasswordChange: Boolean = false
)