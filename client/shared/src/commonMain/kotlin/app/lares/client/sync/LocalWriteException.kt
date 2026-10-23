package app.lares.client.sync

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode

/** A local write was refused before anything was stored. [code] uses the same codes as server rejections. */
open class LocalWriteException(
    val code: ErrorCode,
    val fields: Map<String, FieldErrorCode> = emptyMap(),
    message: String = code.name,
) : Exception(message)

/** The shared `WriteRules` do not allow the change for the current member. */
class LocalForbiddenException(
    message: String,
) : LocalWriteException(ErrorCode.FORBIDDEN, message = message)

/** The merged entity fails the shared validators; [fields] maps field names to codes. */
class LocalValidationException(
    fields: Map<String, FieldErrorCode>,
) : LocalWriteException(ErrorCode.VALIDATION_FAILED, fields, "Invalid fields: ${fields.keys.joinToString()}")
