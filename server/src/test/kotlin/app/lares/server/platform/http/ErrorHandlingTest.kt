package app.lares.server.platform.http

import app.lares.contract.api.ApiError
import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.server.testing.jsonClient
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@Serializable
private data class NameRequest(
    val name: String,
)

class ErrorHandlingTest {
    private fun ApplicationTestBuilder.installTestRoutes() {
        application {
            routing {
                route("/api/v1/test") {
                    get("/api-error") {
                        throw ApiException(ErrorCode.VALIDATION_FAILED, "Name must not be empty", mapOf("name" to FieldErrorCode.REQUIRED))
                    }
                    get("/conflict") { throw ApiException(ErrorCode.LAST_ADMIN, "Last admin") }
                    get("/boom") { throw IllegalStateException("secret internal detail") }
                    post("/echo") { call.respond(call.receive<NameRequest>()) }
                }
            }
        }
    }

    @Test
    fun apiExceptionProducesDocumentedBody() =
        testApp {
            installTestRoutes()
            val response = jsonClient().get("/api/v1/test/api-error")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                """{"error":{"code":"VALIDATION_FAILED","message":"Name must not be empty","fields":{"name":"REQUIRED"}}}""",
                response.bodyAsText(),
            )
        }

    @Test
    fun apiExceptionUsesStatusFromErrorsTable() =
        testApp {
            installTestRoutes()
            val response = jsonClient().get("/api/v1/test/conflict")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ApiError(ErrorCode.LAST_ADMIN, "Last admin"), response.body<ApiErrorBody>().error)
        }

    @Test
    fun unknownExceptionIsInternalWithoutDetails() =
        testApp {
            installTestRoutes()
            val response = jsonClient().get("/api/v1/test/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val text = response.bodyAsText()
            assertEquals("""{"error":{"code":"INTERNAL","message":"Internal server error","fields":{}}}""", text)
            assertFalse(text.contains("secret"))
        }

    @Test
    fun malformedBodyIsValidationError() =
        testApp {
            installTestRoutes()
            val response =
                jsonClient().post("/api/v1/test/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nam""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ApiErrorBody>().error.code)
        }

    @Test
    fun unknownRouteIsNotFound() =
        testApp {
            val response = jsonClient().get("/api/v1/does-not-exist")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun requestIdIsGeneratedOrKept() =
        testApp {
            val generated = client.get("/health/live").headers[HttpHeaders.XRequestId]
            assertNotNull(generated)
            val kept = client.get("/health/live") { header(HttpHeaders.XRequestId, "abc-123") }.headers[HttpHeaders.XRequestId]
            assertEquals("abc-123", kept)
        }
}
