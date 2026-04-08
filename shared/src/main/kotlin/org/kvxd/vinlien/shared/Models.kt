package org.kvxd.vinlien.shared

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artists: List<String> = emptyList(),
    val durationMs: Long,
    val streamUrl: String? = null,
    val artworkUrl: String? = null,
    val canonicalId: String? = null,
    val lastFmUrl: String? = null
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val year: Int? = null,
    val tracks: List<Track> = emptyList()
)

@Serializable
data class SearchResponse(
    val tracks: List<Track>,
    val albums: List<Album>
)

@Serializable
data class User(
    val id: String,
    val username: String,
    val role: String,
    val token: String? = null,
    val requiresPasswordChange: Boolean = false
)

@Serializable
data class HistoryEntry(val userId: String, val track: Track, val timestamp: Long)

@Serializable
data class Playlist(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val tracks: MutableList<Track> = mutableListOf()
)

@Serializable
data class HomeFeed(
    val recentlyPlayed: List<Track>,
    val listenAgain: List<Track>,
    val forgottenFavorites: List<Track>,
    val artists: List<String>
)

@Serializable
data class UserStat(val username: String, val playCount: Int)

@Serializable
data class AdminStats(
    val totalUsers: Int,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val totalPlaytimeMs: Long,
    val topUsers: List<UserStat>
)

@Serializable
data class AdminStatsResponse(
    val stats: AdminStats,
    val pending: List<User>
)

@Serializable
data class ArtistInfo(
    val name: String,
    val bio: String,
    val tags: List<String>,
    val imageUrl: String? = null
)

@Serializable
data class ChangePasswordReq(val newPassword: String)