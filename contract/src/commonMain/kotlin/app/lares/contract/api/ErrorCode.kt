package app.lares.contract.api

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

/**
 * Stable error codes (architecture/api.md § Errors). HTTP status mapping lives on the server.
 * Unknown codes from a newer server decode to [UNKNOWN].
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode {
    VALIDATION_FAILED,
    PROTOCOL_UNSUPPORTED,
    UNAUTHENTICATED,
    SESSION_EXPIRED,
    SESSION_REVOKED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    NOT_FOUND,
    LOGIN_TAKEN,
    CONFLICT,
    LAST_ADMIN,
    BUILTIN_GROUP,
    SETUP_ALREADY_DONE,
    SETUP_CODE_INVALID,
    INVITE_INVALID,
    LOGIN_CODE_INVALID,
    RATE_LIMITED,
    INTERNAL,

    // Sync mutation rejections (architecture/sync.md).
    ENTITY_NOT_FOUND,
    ENTITY_DELETED,
    MUTATION_EXPIRED,
    TAG_NAME_TAKEN,
    PERSON_IS_MEMBER,

    /** Client-side fallback for codes this version does not know. Never sent by the server. */
    UNKNOWN,
}

@OptIn(ExperimentalSerializationApi::class)
internal object ErrorCodeSerializer :
    LenientEnumSerializer<ErrorCode>(ErrorCode.generatedSerializer(), ErrorCode.entries, ErrorCode.UNKNOWN)

/** Field-level validation codes inside [ApiError.fields]. Unknown codes decode to [UNKNOWN]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = FieldErrorCodeSerializer::class)
enum class FieldErrorCode {
    REQUIRED,
    TOO_LONG,
    TOO_SHORT,
    INVALID_FORMAT,
    OUT_OF_RANGE,
    TAKEN,

    /** Client-side fallback for codes this version does not know. Never sent by the server. */
    UNKNOWN,
}

@OptIn(ExperimentalSerializationApi::class)
internal object FieldErrorCodeSerializer :
    LenientEnumSerializer<FieldErrorCode>(FieldErrorCode.generatedSerializer(), FieldErrorCode.entries, FieldErrorCode.UNKNOWN)
