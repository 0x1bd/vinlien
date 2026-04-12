package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordReq(val newPassword: String)