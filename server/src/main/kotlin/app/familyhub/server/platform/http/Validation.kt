package app.familyhub.server.platform.http

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.validation.ValidationErrors

/** Runs contract validators and answers `VALIDATION_FAILED` with all field errors at once. */
inline fun validate(build: ValidationErrors.() -> Unit) {
    val errors = ValidationErrors().apply(build)
    if (!errors.isEmpty) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.fields.keys.joinToString()}", errors.fields)
    }
}
