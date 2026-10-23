package app.familyhub.server.testing

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.Protocol
import app.familyhub.server.AppDeps
import app.familyhub.server.module
import app.familyhub.server.platform.AppConfig
import app.familyhub.server.platform.BuildInfo
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.sync.HouseholdChangeBus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/** Setup code configured for tests (as if from `FAMILYHUB_SETUP_CODE`). */
const val TEST_SETUP_CODE = "ABCDE-12345"

/** Fresh dependencies against the shared test database, with all tables emptied and a [TestClock]. */
fun testDeps(): AppDeps {
    TestDatabase.reset()
    val changeBus = HouseholdChangeBus()
    return AppDeps(
        config = AppConfig.fromEnv(emptyMap()).copy(db = TestDatabase.dbConfig, setupCode = TEST_SETUP_CODE),
        db = AppDatabase(TestDatabase.database, changeBus),
        changeBus = changeBus,
        buildInfo = BuildInfo.load(),
        clock = TestClock(),
        backgroundJobs = false,
    )
}

val AppDeps.testClock: TestClock get() = clock as TestClock

/** Runs [block] against the full application module on an empty database. */
fun testApp(block: suspend ApplicationTestBuilder.(deps: AppDeps) -> Unit) {
    val deps = testDeps()
    testApplication {
        application { module(deps) }
        block(deps)
    }
}

/** JSON client that sends the protocol header by default; pass `protocol = null` to omit it. */
fun ApplicationTestBuilder.jsonClient(protocol: String? = Protocol.VERSION.toString()): HttpClient =
    createClient {
        install(ContentNegotiation) { json(FamilyHubJson) }
        defaultRequest {
            if (protocol != null) header(Protocol.HEADER, protocol)
        }
    }
