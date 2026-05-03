package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.db.History
import org.kvxd.vinlien.server.db.Tracks
import org.kvxd.vinlien.server.db.Users
import org.kvxd.vinlien.shared.models.auth.User

object StatsRepository {

    suspend fun getPendingUsers(): List<User> = dbQuery {
        Users.selectAll()
            .where { Users.role eq "PENDING" }
            .map { User(it[Users.id], it[Users.username], it[Users.role]) }
    }

    suspend fun getAllHistoryRows(): List<ResultRow> = dbQuery {
        (History innerJoin Tracks).selectAll().toList()
    }

    suspend fun getUserNamesMap(): Map<String, String> = dbQuery {
        Users.selectAll().associate { it[Users.id] to it[Users.username] }
    }
}
