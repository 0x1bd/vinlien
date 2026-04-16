package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class RecResult(
    val track: Track,
    val reason: String
)