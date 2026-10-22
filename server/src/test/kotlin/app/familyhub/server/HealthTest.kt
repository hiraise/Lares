package app.familyhub.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun liveReturnsOk() =
        testApplication {
            application { module() }
            val response = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok"}""", response.bodyAsText())
        }
}
