package org.kvxd.vinlien.shared.models.auth

import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordReq(val newPassword: String)
