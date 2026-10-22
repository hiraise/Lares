package app.familyhub.server.platform.http

import app.familyhub.contract.api.ApiErrorBody
import app.familyhub.contract.api.ErrorCode
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolCheckTest {
    private fun ApplicationTestBuilder.installTestRoute() {
        application {
            routing {
                route("/api/v1") {
                    protocolChecked { get("/test/ping") { call.respondText("pong") } }
                }
            }
        }
    }

    @Test
    fun missingHeaderIsValidationError() =
        testApp {
            installTestRoute()
            val response = jsonClient(protocol = null).get("/api/v1/test/ping")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ApiErrorBody>().error.code)
        }

    @Test
    fun nonNumericHeaderIsValidationError() =
        testApp {
            installTestRoute()
            val response = jsonClient(protocol = "abc").get("/api/v1/test/ping")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun unsupportedVersionIsUpgradeRequired() =
        testApp {
            installTestRoute()
            for (version in listOf("99", "0")) {
                val response = jsonClient(protocol = version).get("/api/v1/test/ping")
                assertEquals(HttpStatusCode.UpgradeRequired, response.status, "version $version")
                assertEquals(ErrorCode.PROTOCOL_UNSUPPORTED, response.body<ApiErrorBody>().error.code)
            }
        }

    @Test
    fun supportedVersionPasses() =
        testApp {
            installTestRoute()
            val response = jsonClient(protocol = "1").get("/api/v1/test/ping")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("pong", response.bodyAsText())
        }
}
