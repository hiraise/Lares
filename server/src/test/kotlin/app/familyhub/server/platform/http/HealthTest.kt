package app.familyhub.server.platform.http

import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.sync.HouseholdChangeBus
import app.familyhub.server.testing.testApp
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** FND-070: health checks. */
class HealthTest {
    @Test
    fun liveReturnsOk() =
        testApp {
            val response = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok"}""", response.bodyAsText())
        }

    @Test
    fun readyReturnsOkWhenDatabaseAnswers() =
        testApp {
            val response = client.get("/health/ready")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun readyReturns503WhenDatabaseIsUnreachable() {
        // The pool connects lazily, so the failure surfaces in the readiness check rather than at startup.
        val unreachable =
            HikariDataSource().apply {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:1/none"
                connectionTimeout = 250
                initializationFailTimeout = -1
            }
        unreachable.use { dataSource ->
            val db = AppDatabase(AppDatabase.connect(dataSource), HouseholdChangeBus())
            testApplication {
                application {
                    installHttpPlumbing()
                    routing { healthRoutes(db) }
                }
                val response = client.get("/health/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("""{"status":"unavailable"}""", response.bodyAsText())
            }
        }
    }
}
