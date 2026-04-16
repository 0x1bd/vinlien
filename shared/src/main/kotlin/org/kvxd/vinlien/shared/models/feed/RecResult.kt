package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class RecResult(
    val track: Track,
    val reason: String
)
