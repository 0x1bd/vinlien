package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable

@Serializable
data class SkipRequest(
    val trackId: String,
    val artist: String,
    val playedMs: Long
)
