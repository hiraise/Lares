package app.lares.server.platform.http

import app.lares.contract.api.ErrorCode
import app.lares.contract.validation.ValidationErrors

/** Runs contract validators and answers `VALIDATION_FAILED` with all field errors at once. */
inline fun validate(build: ValidationErrors.() -> Unit) {
    val errors = ValidationErrors().apply(build)
    if (!errors.isEmpty) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.fields.keys.joinToString()}", errors.fields)
    }
}
