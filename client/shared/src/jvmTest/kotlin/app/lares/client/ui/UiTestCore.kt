package app.lares.client.ui

import app.lares.client.auth.SessionManager
import app.lares.client.di.jvmPlatformModule
import app.lares.client.di.sharedModule
import app.lares.contract.api.ApiError
import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.InstanceInfo
import app.lares.contract.api.LaresJson
import app.lares.contract.api.ProtocolRange
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.Me
import app.lares.contract.permissions.Permission
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

typealias Handler = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

/**
 * A client core with the UI module on a mocked server. ViewModels run on `Dispatchers.Default` as their main
 * dispatcher, so tests wait for states with [awaitState] instead of advancing virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiTestCore(
    /**
     * The main dispatcher of ViewModels. Compose UI tests pass `Dispatchers.Unconfined`: lifecycle code checks that it
     * runs on the "main thread" by asking whether Main needs to dispatch.
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Default,
    handler: Handler,
) : AutoCloseable {
    val requests = mutableListOf<HttpRequestData>()

    val app: KoinApplication =
        koinApplication {
            val engine =
                MockEngine { request ->
                    synchronized(requests) { requests += request }
                    handler(request)
                }
            // Loaded last, so it replaces the platform's real engine.
            val mockServer = module { single<HttpClientEngine> { engine } }
            modules(sharedModule(jvmPlatformModule()), uiModule(), mockServer)
        }

    val koin: Koin get() = app.koin

    val session: SessionManager get() = koin.get()

    init {
        Dispatchers.setMain(mainDispatcher)
    }

    override fun close() {
        runBlocking { koin.get<app.lares.client.sync.SyncEngine>().stop() }
        koin.close()
        // Main is not reset: ViewModels of a test are never cleared, and their collectors may still resume on Main
        // after the test. Every test installs its own main dispatcher anyway.
    }

    fun paths(): List<String> = synchronized(requests) { requests.map { it.url.encodedPath } }
}

fun <T> awaitState(
    flow: Flow<T>,
    predicate: (T) -> Boolean,
): T = runBlocking { withTimeout(5_000) { flow.first(predicate) } }

fun <T> MockRequestHandleScope.json(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(LaresJson.encodeToString(serializer, value), status, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.apiError(
    status: HttpStatusCode,
    code: ErrorCode,
    fields: Map<String, FieldErrorCode> = emptyMap(),
) = json(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code.name, fields)), status)

fun MockRequestHandleScope.instance(initialized: Boolean = true) =
    json(InstanceInfo.serializer(), InstanceInfo(initialized, "0.1.0", ProtocolRange(1, 1)))

val testMe =
    Me(
        userId = "u1",
        memberId = "m1",
        householdId = "h1",
        householdName = "Petrovy",
        personId = "p1",
        displayName = "Olga",
        login = "olga",
        hasPassword = true,
        isAdmin = false,
        permissions = setOf(Permission.TASKS_VIEW),
    )

fun MockRequestHandleScope.authResult(me: Me = testMe): HttpResponseData {
    val now = Clock.System.now()
    return json(AuthResult.serializer(), AuthResult("access", now + 1.hours, "refresh", now + 1000.hours, "s1", me))
}

/** Unhandled paths (sync after login) answer 503, which the sync engine treats as a temporary failure. */
fun MockRequestHandleScope.unavailable() = apiError(HttpStatusCode.ServiceUnavailable, ErrorCode.INTERNAL)
