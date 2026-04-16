package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class RadioRequest(
    val seedTrack: Track,
    val queue: List<Track>,
    val tracksPlayedInSession: Int = 0,
    val sessionArtists: List<String> = emptyList(),
    val queueSize: Int = 10
)