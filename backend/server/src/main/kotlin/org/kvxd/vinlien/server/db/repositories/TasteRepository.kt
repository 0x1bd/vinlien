package org.kvxd.vinlien.server.db.repositories

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.kvxd.vinlien.server.db.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.TasteCapsuleModel
import org.kvxd.vinlien.server.db.TasteCapsules
import org.kvxd.vinlien.server.TasteGraph

object TasteRepository {

    suspend fun getCapsules(userId: String): List<TasteCapsuleModel> = dbQuery {
        TasteCapsules.selectAll()
            .where { TasteCapsules.userId eq userId }
            .map { row ->
                TasteCapsuleModel(
                    key = row[TasteCapsules.capsuleKey],
                    label = row[TasteCapsules.label],
                    features = TasteGraph.deserializeFeatures(row[TasteCapsules.features]),
                    weight = row[TasteCapsules.weight]
                )
            }
    }

    suspend fun persistTasteCapsules(userId: String, capsules: List<TasteCapsuleModel>) = dbQuery {
        TasteCapsules.deleteWhere { TasteCapsules.userId eq userId }
        val now = System.currentTimeMillis()
        capsules.forEach { capsule ->
            TasteCapsules.insert {
                it[this.userId] = userId
                it[capsuleKey] = capsule.key
                it[label] = capsule.label.take(120)
                it[features] = TasteGraph.serializeFeatures(capsule.features)
                it[weight] = capsule.weight
                it[updatedAt] = now
            }
        }
    }
}
