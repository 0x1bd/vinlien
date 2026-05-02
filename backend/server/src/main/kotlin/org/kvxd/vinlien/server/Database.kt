package org.kvxd.vinlien.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.shared.models.media.Track
import org.mindrot.jbcrypt.BCrypt

object Users : Table("vl_users") {
    val id = varchar("id", 36)
    val username = varchar("username", 50).uniqueIndex()
    val role = varchar("role", 20)
    val passwordHash = varchar("password_hash", 100)
    override val primaryKey = PrimaryKey(id)
}

object Tracks : Table("vl_tracks") {
    val id = varchar("id", 100)
    val title = varchar("title", 255)
    val artist = varchar("artist", 255)
    val durationMs = long("duration_ms")
    val streamUrl = text("stream_url").nullable()
    val artworkUrl = text("artwork_url").nullable()
    val canonicalId = varchar("canonical_id", 100).nullable()
    val lastFmUrl = text("last_fm_url").nullable()
    val albumTitle = varchar("album_title", 255).nullable()
    val albumId = varchar("album_id", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Playlists : Table("vl_playlists") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val imageUrl = text("image_url").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PlaylistTracks : Table("vl_playlist_tracks") {
    val id = integer("id").autoIncrement()
    val playlistId = varchar("playlist_id", 36).references(Playlists.id, onDelete = ReferenceOption.CASCADE)
    val trackId = varchar("track_id", 100).references(Tracks.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    override val primaryKey = PrimaryKey(id)
}

object History : Table("vl_history") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val trackId = varchar("track_id", 100).references(Tracks.id, onDelete = ReferenceOption.CASCADE)
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)
}

object SkipEvents : Table("vl_skip_events") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val trackId = varchar("track_id", 100)
    val artist = varchar("artist", 255)
    val playedMs = long("played_ms")
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)
}

object PlayEvents : Table("vl_play_events") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val trackId = varchar("track_id", 100).references(Tracks.id, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar("event_type", 32)
    val eventSource = varchar("source", 32).nullable()
    val sessionId = varchar("session_id", 64).nullable()
    val playedMs = long("played_ms").default(0L)
    val durationMs = long("duration_ms").default(0L)
    val wasManual = bool("was_manual").default(false)
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)
}

object TrackFeatures : Table("vl_track_features") {
    val trackId = varchar("track_id", 100).references(Tracks.id, onDelete = ReferenceOption.CASCADE)
    val features = text("features")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(trackId)
}

object TasteCapsules : Table("vl_taste_capsules") {
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val capsuleKey = varchar("capsule_key", 40)
    val label = varchar("label", 120)
    val features = text("features")
    val weight = double("weight")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId, capsuleKey)
}

object DatabaseFactory {
    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = Config.data.dbUrl
            username = Config.data.dbUser
            password = Config.data.dbPass
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                Tracks,
                Playlists,
                PlaylistTracks,
                History,
                SkipEvents,
                PlayEvents,
                TrackFeatures,
                TasteCapsules
            )

            if (Users.selectAll().where { Users.username eq "admin" }.empty()) {
                Users.insert {
                    it[id] = "0"
                    it[username] = "admin"
                    it[role] = "ADMIN"
                    it[passwordHash] = BCrypt.hashpw("admin", BCrypt.gensalt())
                }
            }
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    fun ResultRow.toTrack() = Normalizer.normalizeTrack(
        Track(
            id = this[Tracks.id],
            title = this[Tracks.title],
            artist = this[Tracks.artist],
            durationMs = this[Tracks.durationMs],
            streamUrl = this[Tracks.streamUrl],
            artworkUrl = this[Tracks.artworkUrl],
            canonicalId = this[Tracks.canonicalId],
            lastFmUrl = this[Tracks.lastFmUrl],
            albumTitle = this[Tracks.albumTitle],
            albumId = this[Tracks.albumId]
        )
    )

    fun insertOrUpdateTrack(track: Track) {
        val exists = Tracks.selectAll().where { Tracks.id eq track.id }.count() > 0
        if (!exists) {
            Tracks.insert {
                it[id] = track.id
                it[title] = track.title
                it[artist] = track.artist
                it[durationMs] = track.durationMs
                it[streamUrl] = track.streamUrl
                it[artworkUrl] = track.artworkUrl
                it[canonicalId] = track.canonicalId
                it[lastFmUrl] = track.lastFmUrl
                it[albumTitle] = track.albumTitle
                it[albumId] = track.albumId
            }
        } else {
            Tracks.update({ Tracks.id eq track.id }) {
                it[title] = track.title
                it[artist] = track.artist
                it[durationMs] = track.durationMs
                it[streamUrl] = track.streamUrl
                if (track.artworkUrl != null) it[artworkUrl] = track.artworkUrl
                if (track.canonicalId != null) it[canonicalId] = track.canonicalId
                if (track.lastFmUrl != null) it[lastFmUrl] = track.lastFmUrl
                if (track.albumTitle != null) it[albumTitle] = track.albumTitle
                if (track.albumId != null) it[albumId] = track.albumId
            }
        }
    }
}
