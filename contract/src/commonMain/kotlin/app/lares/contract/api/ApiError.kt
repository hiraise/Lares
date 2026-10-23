package app.lares.contract.api

import kotlinx.serialization.Serializable

/** Body of every non-2xx response: `{"error": {...}}`. */
@Serializable
data class ApiErrorBody(
    val error: ApiError,
)

/** [message] is English and meant for logs; clients show localized text for [code]. */
@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val fields: Map<String, FieldErrorCode> = emptyMap(),
)
