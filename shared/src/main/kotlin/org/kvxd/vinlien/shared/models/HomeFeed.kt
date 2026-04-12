package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class HomeFeed(
    val recentlyPlayed: List<Track>,
    val listenAgain: List<Track>,
    val forgottenFavorites: List<Track>,
    val artists: List<String>
)