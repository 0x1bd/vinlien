package org.kvxd.vinlien.shared.models.media

import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntry(val userId: String, val track: Track, val timestamp: Long)
