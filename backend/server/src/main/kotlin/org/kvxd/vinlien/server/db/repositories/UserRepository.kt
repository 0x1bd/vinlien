package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.shared.models.auth.User
import java.util.UUID

object UserRepository {
    suspend fun findByUsername(username: String): ResultRow? = dbQuery {
        Users.selectAll().where { Users.username eq username }.singleOrNull()
    }

    suspend fun userExistsByUsername(username: String): Boolean = dbQuery {
        Users.selectAll().where { Users.username eq username }.count() > 0
    }

    suspend fun userExistsById(id: String): Boolean = dbQuery {
        Users.selectAll().where { Users.id eq id }.count() > 0
    }

    suspend fun createUser(username: String, passwordHash: String, role: String = "PENDING"): String = dbQuery {
        val newId = UUID.randomUUID().toString()
        Users.insert {
            it[id] = newId
            it[this.username] = username
            it[this.role] = role
            it[this.passwordHash] = passwordHash
        }
        newId
    }

    suspend fun updatePasswordByUsername(username: String, newPasswordHash: String) = dbQuery {
        Users.update({ Users.username eq username }) {
            it[passwordHash] = newPasswordHash
        }
    }

    suspend fun updatePasswordById(id: String, newPasswordHash: String) = dbQuery {
        Users.update({ Users.id eq id }) {
            it[passwordHash] = newPasswordHash
        }
    }

    suspend fun approveUser(id: String) = dbQuery {
        Users.update({ Users.id eq id }) {
            it[role] = "APPROVED"
        }
    }

    suspend fun getAllUsers(): List<User> = dbQuery {
        Users.selectAll().map { User(it[Users.id], it[Users.username], it[Users.role]) }
    }

    suspend fun deleteUser(id: String) = dbQuery {
        Users.deleteWhere { Users.id eq id }
    }

    suspend fun isAdmin(username: String): Boolean = dbQuery {
        Users.selectAll().where { (Users.username eq username) and (Users.role eq "ADMIN") }.count() > 0
    }

    suspend fun deleteUserData(userId: String) = dbQuery {
        History.deleteWhere { History.userId eq userId }
        SkipEvents.deleteWhere { SkipEvents.userId eq userId }
        PlayEvents.deleteWhere { PlayEvents.userId eq userId }
        TasteCapsules.deleteWhere { TasteCapsules.userId eq userId }
        Playlists.deleteWhere { Playlists.userId eq userId }
    }
}
