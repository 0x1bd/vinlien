package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class RadioRequest(
    val seedTrack: Track,
    val additionalSeeds: List<Track> = emptyList(),
    val queue: List<Track>,
    val tracksPlayedInSession: Int = 0,
    val sessionArtists: List<String> = emptyList(),
    val queueSize: Int = 10
)
