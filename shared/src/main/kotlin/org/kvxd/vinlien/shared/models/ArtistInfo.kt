package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class ArtistInfo(
    val name: String,
    val bio: String,
    val tags: List<String>,
    val imageUrl: String? = null
)