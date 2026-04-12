package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val year: Int? = null,
    val tracks: List<Track> = emptyList()
)