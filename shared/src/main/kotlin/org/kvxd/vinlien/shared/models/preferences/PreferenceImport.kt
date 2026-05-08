package org.kvxd.vinlien.shared.models.preferences

import kotlinx.serialization.Serializable

@Serializable
data class PreferenceImportRequest(
    val fileName: String,
    val content: String
)

@Serializable
data class PreferenceImportResponse(
    val source: String,
    val imported: Int,
    val skipped: Int,
    val message: String
)