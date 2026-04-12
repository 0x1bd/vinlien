package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val tracks: List<Track>,
    val albums: List<Album>
)