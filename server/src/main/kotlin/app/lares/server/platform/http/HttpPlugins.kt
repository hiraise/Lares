package app.lares.server.platform.http

import app.lares.contract.api.ApiError
import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.util.UUID

private val log = KotlinLogging.logger {}

/** MDC key of the request id; referenced by the logback configs. */
const val REQUEST_ID_MDC = "requestId"

private val requestIdPattern = Regex("[A-Za-z0-9._-]{1,64}")

fun Application.installHttpPlumbing() {
    // The server always runs behind Caddy, which sets X-Forwarded-For; client IPs feed the failed-attempt limiter.
    install(XForwardedHeaders)
    install(ContentNegotiation) {
        json(LaresJson)
    }
    install(CallId) {
        // A client-supplied id is kept only if it is short and harmless in logs.
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { requestIdPattern.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        // Default format: status, method and path only. Bodies are never logged.
        callIdMdc(REQUEST_ID_MDC)
        // Plain text in both log formats (ANSI colors would end up inside JSON messages).
        disableDefaultColors()
        filter { call -> !call.request.path().startsWith("/health/") }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondError(cause.code, cause.message ?: cause.code.name, cause.fields)
        }
        exception<BadRequestException> { call, cause ->
            call.respondError(ErrorCode.VALIDATION_FAILED, cause.message ?: "Bad request")
        }
        exception<ContentTransformationException> { call, cause ->
            call.respondError(ErrorCode.VALIDATION_FAILED, cause.message ?: "Malformed request body")
        }
        exception<NotFoundException> { call, _ ->
            call.respondError(ErrorCode.NOT_FOUND, "Not found")
        }
        exception<Throwable> { call, cause ->
            log.error(cause) { "Unhandled exception" }
            call.respondError(ErrorCode.INTERNAL, "Internal server error")
        }
    }
}

private suspend fun ApplicationCall.respondError(
    code: ErrorCode,
    message: String,
    fields: Map<String, FieldErrorCode> = emptyMap(),
) {
    respond(code.httpStatus, ApiErrorBody(ApiError(code, message, fields)))
}
