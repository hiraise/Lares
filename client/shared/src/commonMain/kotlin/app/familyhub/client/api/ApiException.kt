package app.familyhub.client.api

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode

/** A non-2xx answer of the server, decoded from its error body (architecture/api.md § Errors). */
class ApiException(
    val code: ErrorCode,
    message: String,
    val fields: Map<String, FieldErrorCode> = emptyMap(),
    val httpStatus: Int? = null,
) : Exception(message)

/** Session events the app reacts to. */
sealed interface AuthEvent {
    /** The session ended on the server (revoked, member removed, refresh failed). Tokens are already cleared. */
    data object LoggedOut : AuthEvent
}
