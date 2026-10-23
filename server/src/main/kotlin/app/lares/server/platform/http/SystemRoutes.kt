package app.lares.server.platform.http

import app.lares.contract.api.InstanceInfo
import app.lares.contract.api.Protocol
import app.lares.contract.api.ProtocolRange
import app.lares.server.platform.BuildInfo
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdTable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.selectAll

private val log = KotlinLogging.logger {}

@Serializable
data class HealthStatus(
    val status: String,
)

/** FND-070: liveness and readiness probes. */
fun Route.healthRoutes(db: AppDatabase) {
    get("/health/live") {
        call.respond(HealthStatus("ok"))
    }
    get("/health/ready") {
        val ready =
            try {
                db.dbQuery { exec("SELECT 1") { rs -> rs.next() } } == true
            } catch (e: Exception) {
                log.warn { "Readiness check failed: ${e.message}" }
                false
            }
        if (ready) {
            call.respond(HealthStatus("ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthStatus("unavailable"))
        }
    }
}

/** `GET /api/v1/instance` (FND-001). Public and exempt from the protocol check, so old clients can learn the range. */
fun Route.instanceRoute(
    db: AppDatabase,
    buildInfo: BuildInfo,
) {
    get("/instance") {
        val initialized = db.dbQuery { HouseholdTable.selectAll().limit(1).any() }
        call.respond(
            InstanceInfo(
                initialized = initialized,
                serverVersion = buildInfo.version,
                protocol = ProtocolRange(min = Protocol.MIN_SUPPORTED, max = Protocol.VERSION),
            ),
        )
    }
}
