package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class RecRequest(
    val queue: List<Track>,
    val sessionArtists: List<String> = emptyList(),
    val noveltyBudget: Float = 0.30f
)
