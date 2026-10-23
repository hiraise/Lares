package app.lares.contract.api

import kotlinx.serialization.Serializable

/** Response of `GET /api/v1/instance` (FND-001). */
@Serializable
data class InstanceInfo(
    val initialized: Boolean,
    val serverVersion: String,
    val protocol: ProtocolRange,
)

@Serializable
data class ProtocolRange(
    val min: Int,
    val max: Int,
)
