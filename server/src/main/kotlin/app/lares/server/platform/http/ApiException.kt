package app.lares.server.platform.http

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import io.ktor.http.HttpStatusCode

/** Thrown by server code to answer with a documented error (architecture/api.md § Errors). */
class ApiException(
    val code: ErrorCode,
    message: String,
    val fields: Map<String, FieldErrorCode> = emptyMap(),
) : RuntimeException(message)

/** HTTP status per the API errors table. Sync-only codes normally travel inside push responses, not as HTTP errors. */
val ErrorCode.httpStatus: HttpStatusCode
    get() =
        when (this) {
            ErrorCode.VALIDATION_FAILED -> {
                HttpStatusCode.BadRequest
            }

            ErrorCode.PROTOCOL_UNSUPPORTED -> {
                HttpStatusCode.UpgradeRequired
            }

            ErrorCode.UNAUTHENTICATED,
            ErrorCode.SESSION_EXPIRED,
            ErrorCode.SESSION_REVOKED,
            ErrorCode.INVALID_CREDENTIALS,
            -> {
                HttpStatusCode.Unauthorized
            }

            ErrorCode.FORBIDDEN, ErrorCode.SETUP_CODE_INVALID -> {
                HttpStatusCode.Forbidden
            }

            ErrorCode.NOT_FOUND, ErrorCode.INVITE_INVALID, ErrorCode.LOGIN_CODE_INVALID, ErrorCode.ENTITY_NOT_FOUND -> {
                HttpStatusCode.NotFound
            }

            ErrorCode.LOGIN_TAKEN,
            ErrorCode.CONFLICT,
            ErrorCode.LAST_ADMIN,
            ErrorCode.BUILTIN_GROUP,
            ErrorCode.SETUP_ALREADY_DONE,
            ErrorCode.ENTITY_DELETED,
            ErrorCode.MUTATION_EXPIRED,
            ErrorCode.TAG_NAME_TAKEN,
            ErrorCode.PERSON_IS_MEMBER,
            -> {
                HttpStatusCode.Conflict
            }

            ErrorCode.RATE_LIMITED -> {
                HttpStatusCode.TooManyRequests
            }

            ErrorCode.INTERNAL, ErrorCode.UNKNOWN -> {
                HttpStatusCode.InternalServerError
            }
        }
