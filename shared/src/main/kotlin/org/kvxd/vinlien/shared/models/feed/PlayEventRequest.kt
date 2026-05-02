package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class PlayEventRequest(
    val track: Track,
    val eventType: String,
    val playedMs: Long = 0L,
    val durationMs: Long = 0L,
    val source: String? = null,
    val sessionId: String? = null,
    val wasManual: Boolean = false
)
