package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class RadioResponse(
    val tracks: List<RecResult>,
    val seedTrack: Track
)