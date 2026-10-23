package app.lares.server.testing

import app.lares.contract.api.LaresJson
import app.lares.contract.api.Protocol
import app.lares.server.AppDeps
import app.lares.server.module
import app.lares.server.platform.AppConfig
import app.lares.server.platform.BuildInfo
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.sync.HouseholdChangeBus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/** Setup code configured for tests (as if from `LARES_SETUP_CODE`). */
const val TEST_SETUP_CODE = "ABCDE-12345"

/**
 * Fresh dependencies against the shared test database, with all tables emptied and a [TestClock].
 * Integration tests pass a clock near the real time, because clients stamp mutations with their own clock.
 */
fun testDeps(
    clock: TestClock = TestClock(),
    configure: (AppConfig) -> AppConfig = { it },
): AppDeps {
    TestDatabase.reset()
    val changeBus = HouseholdChangeBus()
    return AppDeps(
        config = configure(AppConfig.fromEnv(emptyMap()).copy(db = TestDatabase.dbConfig, setupCode = TEST_SETUP_CODE)),
        db = AppDatabase(TestDatabase.database, changeBus),
        changeBus = changeBus,
        buildInfo = BuildInfo.load(),
        clock = clock,
        backgroundJobs = false,
    )
}

val AppDeps.testClock: TestClock get() = clock as TestClock

/** Runs [block] against the full application module on an empty database. */
fun testApp(
    configure: (AppConfig) -> AppConfig = { it },
    block: suspend ApplicationTestBuilder.(deps: AppDeps) -> Unit,
) {
    val deps = testDeps(configure = configure)
    testApplication {
        application { module(deps) }
        block(deps)
    }
}

/** JSON client that sends the protocol header by default; pass `protocol = null` to omit it. */
fun ApplicationTestBuilder.jsonClient(protocol: String? = Protocol.VERSION.toString()): HttpClient =
    createClient {
        install(ContentNegotiation) { json(LaresJson) }
        defaultRequest {
            if (protocol != null) header(Protocol.HEADER, protocol)
        }
    }
