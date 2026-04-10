package org.kvxd.vinlien.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.kvxd.vinlien.backends.Normalizer
import org.kvxd.vinlien.shared.Track
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
            SchemaUtils.createMissingTablesAndColumns(Users, Tracks, Playlists, PlaylistTracks, History)

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
            lastFmUrl = this[Tracks.lastFmUrl]
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
            }
        }
    }
}
