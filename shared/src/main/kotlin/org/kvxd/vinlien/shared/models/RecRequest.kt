package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class RecRequest(
    val queue: List<Track>,
    val sessionArtists: List<String> = emptyList(),
    val noveltyBudget: Float = 0.30f
)