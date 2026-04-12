package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artists: List<String> = emptyList(),
    val durationMs: Long,
    val streamUrl: String? = null,
    val artworkUrl: String? = null,
    val canonicalId: String? = null,
    val lastFmUrl: String? = null
)