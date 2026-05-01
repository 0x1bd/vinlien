package org.kvxd.vinlien.server.services

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.*
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.DatabaseFactory.toTrack
import org.kvxd.vinlien.shared.models.media.Playlist
import org.kvxd.vinlien.shared.models.media.Track
import java.util.UUID

object PlaylistService {

    suspend fun getForUser(userId: String): List<Playlist> = dbQuery {
        val userPlaylists = Playlists.selectAll()
            .where { Playlists.userId eq userId }
            .associate {
                it[Playlists.id] to Playlist(
                    id = it[Playlists.id],
                    userId = userId,
                    name = it[Playlists.name],
                    description = it[Playlists.description],
                    imageUrl = it[Playlists.imageUrl]
                )
            }.toMutableMap()

        val duplicatesToRemove = mutableListOf<String>()
        userPlaylists.values.groupBy { it.name }
            .filter { (name, list) -> (name == "Liked Songs" || name == "Disliked Songs") && list.size > 1 }
            .forEach { (_, list) ->
                val toRemove = list.drop(1)
                toRemove.forEach {
                    duplicatesToRemove.add(it.id)
                    userPlaylists.remove(it.id)
                }
            }

        if (duplicatesToRemove.isNotEmpty()) {
            PlaylistTracks.deleteWhere { PlaylistTracks.playlistId inList duplicatesToRemove }
            Playlists.deleteWhere { Playlists.id inList duplicatesToRemove }
        }

        ensureSystemPlaylist(userId, "Liked Songs", userPlaylists)
        ensureSystemPlaylist(userId, "Disliked Songs", userPlaylists)

        if (userPlaylists.isNotEmpty()) {
            (PlaylistTracks innerJoin Tracks).selectAll()
                .where { PlaylistTracks.playlistId inList userPlaylists.keys }
                .orderBy(PlaylistTracks.position to SortOrder.ASC)
                .forEach { row ->
                    userPlaylists[row[PlaylistTracks.playlistId]]?.tracks?.add(row.toTrack())
                }
        }
        userPlaylists.values.toList()
    }

    suspend fun create(userId: String, name: String): Playlist {
        val newId = UUID.randomUUID().toString()
        dbQuery {
            Playlists.insert {
                it[id] = newId
                it[this.userId] = userId
                it[this.name] = name
            }
        }
        return Playlist(newId, userId, name)
    }

    suspend fun ownsPlaylist(userId: String, playlistId: String): Boolean = dbQuery {
        Playlists.selectAll()
            .where { (Playlists.id eq playlistId) and (Playlists.userId eq userId) }
            .any()
    }

    suspend fun updateInfo(playlistId: String, name: String, description: String?, imageUrl: String?) = dbQuery {
        Playlists.update({ Playlists.id eq playlistId }) {
            it[Playlists.name] = name
            it[Playlists.description] = description
            it[Playlists.imageUrl] = imageUrl
        }
    }

    suspend fun delete(playlistId: String) = dbQuery {
        Playlists.deleteWhere { Playlists.id eq playlistId }
    }

    suspend fun toggleTrack(userId: String, track: Track, targetName: String, oppositeName: String) = dbQuery {
        DatabaseFactory.insertOrUpdateTrack(track)

        val oppositeId = findPlaylistId(userId, oppositeName)
        if (oppositeId != null) {
            PlaylistTracks.deleteWhere { (playlistId eq oppositeId) and (trackId eq track.id) }
        }

        val targetId = findPlaylistId(userId, targetName) ?: return@dbQuery

        val alreadyPresent = PlaylistTracks.selectAll()
            .where { (PlaylistTracks.playlistId eq targetId) and (PlaylistTracks.trackId eq track.id) }
            .any()

        if (alreadyPresent) {
            PlaylistTracks.deleteWhere { (playlistId eq targetId) and (trackId eq track.id) }
        } else {
            val nextPos = (PlaylistTracks.selectAll()
                .where { PlaylistTracks.playlistId eq targetId }
                .maxByOrNull { it[PlaylistTracks.position] }
                ?.get(PlaylistTracks.position) ?: 0) + 1
            PlaylistTracks.insert {
                it[playlistId] = targetId
                it[trackId] = track.id
                it[position] = nextPos
            }
        }
    }

    suspend fun addTrack(playlistId: String, track: Track) = dbQuery {
        DatabaseFactory.insertOrUpdateTrack(track)
        val nextPos = (PlaylistTracks.selectAll()
            .where { PlaylistTracks.playlistId eq playlistId }
            .maxByOrNull { it[PlaylistTracks.position] }
            ?.get(PlaylistTracks.position) ?: 0) + 1
        PlaylistTracks.insert {
            it[this.playlistId] = playlistId
            it[trackId] = track.id
            it[position] = nextPos
        }
    }

    suspend fun replaceTracks(playlistId: String, tracks: List<Track>) = dbQuery {
        tracks.forEach { DatabaseFactory.insertOrUpdateTrack(it) }
        PlaylistTracks.deleteWhere { PlaylistTracks.playlistId eq playlistId }
        tracks.forEachIndexed { idx, track ->
            PlaylistTracks.insert {
                it[this.playlistId] = playlistId
                it[trackId] = track.id
                it[position] = idx
            }
        }
    }

    private fun findPlaylistId(userId: String, name: String): String? =
        Playlists.selectAll()
            .where { (Playlists.userId eq userId) and (Playlists.name eq name) }
            .singleOrNull()?.get(Playlists.id)

    private fun ensureSystemPlaylist(userId: String, name: String, map: MutableMap<String, Playlist>) {
        if (map.values.none { it.name == name }) {
            val newId = UUID.randomUUID().toString()
            Playlists.insert {
                it[id] = newId
                it[this.userId] = userId
                it[this.name] = name
            }
            map[newId] = Playlist(newId, userId, name)
        }
    }
}
