package org.kvxd.vinlien.shared.models.feed

import kotlinx.serialization.Serializable
import org.kvxd.vinlien.shared.models.media.Track

@Serializable
data class HomeFeed(
    val recentlyPlayed: List<Track>,
    val listenAgain: List<Track>,
    val forgottenFavorites: List<Track>,
    val artists: List<String>
)
