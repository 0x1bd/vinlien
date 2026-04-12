package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntry(val userId: String, val track: Track, val timestamp: Long)