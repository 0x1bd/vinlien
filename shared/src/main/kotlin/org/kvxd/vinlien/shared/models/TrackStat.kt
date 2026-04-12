package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class TrackStat(val title: String, val artist: String, val playCount: Int)
