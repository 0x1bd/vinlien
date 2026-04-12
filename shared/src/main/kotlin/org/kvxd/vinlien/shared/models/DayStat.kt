package org.kvxd.vinlien.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class DayStat(val day: String, val count: Int)
