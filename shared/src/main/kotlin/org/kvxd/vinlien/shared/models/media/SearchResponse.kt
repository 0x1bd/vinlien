package org.kvxd.vinlien.shared.models.media

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val tracks: List<Track>,
    val albums: List<Album>
)
