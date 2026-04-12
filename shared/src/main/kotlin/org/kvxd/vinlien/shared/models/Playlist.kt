package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val tracks: MutableList<Track> = mutableListOf()
)