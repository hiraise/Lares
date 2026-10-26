package app.lares.client.data.location

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.auth.Tokens
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.tasks.TestClock
import app.lares.client.notifications.NotificationPoster
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.defaultEntityAdapters
import app.lares.client.sync.testLocalDb
import app.lares.contract.api.ApiError
import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Member ids of the fixture family. */
const val OLGA = "01928c4e-0000-7000-8000-00000000000a"
const val DIMA = "01928c4e-0000-7000-8000-00000000000b"
const val ANYA = "01928c4e-0000-7000-8000-00000000000c"
const val P_OLGA = "01928c4e-0000-7000-8000-0000000000a1"
const val P_DIMA = "01928c4e-0000-7000-8000-0000000000b1"
const val P_ANYA = "01928c4e-0000-7000-8000-0000000000c1"
const val HOME_ID = "01928c4e-0000-7000-8000-0000000000e1"
const val SCHOOL_ID = "01928c4e-0000-7000-8000-0000000000e2"

val LOC_ADULT = arrayOf(Permission.LOCATION_VIEW, Permission.LOCATION_MANAGE_PLACES, Permission.LOCATION_PAUSE_OWN)
val LOC_KID = arrayOf(Permission.LOCATION_VIEW)

val HOME = PlaceData("Дом", PlaceIcon.HOME, 55.7963, 37.5378, 120, createdBy = OLGA)
val SCHOOL = PlaceData("Школа №12", PlaceIcon.SCHOOL, 55.8001, 37.5329, 150, createdBy = OLGA)

/** Records tracking calls. */
class FakeTracking : LocationTracking {
    val calls = mutableListOf<String>()

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun freshFix() {
        calls += "freshFix"
    }

    override fun pauseChanged() {
        calls += "pauseChanged"
    }
}

/**
 * One offline client core for location tests: in-memory database, a never-started sync engine, a MockEngine whose
 * answers the test sets in [server], hand-driven [ticks], a fake live socket ([live]) and a [TestClock]. Starts on
 * Wednesday 2026-10-21 13:00 in Moscow (10:00 UTC).
 */
class LocationFixture(
    start: Instant = Instant.parse("2026-10-21T10:00:00Z"),
) {
    val clock = TestClock(start)
    val localDb = testLocalDb()
    val adapters = defaultEntityAdapters(clock)
    val requests = mutableListOf<HttpRequestData>()

    /** The server; throwing an `IOException` acts as no network. */
    var server: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respondError(HttpStatusCode.ServiceUnavailable) }
    private val tokens = InMemoryTokenStore()
    val api =
        LaresApi(
            createHttpClient(
                MockEngine { request ->
                    synchronized(requests) { requests += request }
                    server(request)
                },
            ),
            InMemoryServerStore("http://family.test"),
            tokens,
            clock,
        )
    private val scope = CoroutineScope(Job())
    private val engine = SyncEngine(api, localDb, adapters, Materializer(adapters), clock, scope)
    val writer = LocalWriter(localDb, adapters, clock, engine)
    val permissions = PermissionsRepository(localDb)
    val placesRepository = PlacesRepository(localDb, permissions)
    val placesWriter = PlacesWriter(writer, localDb, clock)
    val ticks = MutableStateFlow(start)
    val tracking = FakeTracking()
    val posted = mutableListOf<Pair<NotificationChannel, String>>()
    val queue = PositionQueue(localDb)
    val controller =
        SharingController(
            api,
            localDb,
            tracking,
            AllowAllLocationDevice(),
            queue,
            NotificationPoster { _, channel, title, _, _ -> posted += channel to title },
            EnglishLocationTexts(),
            HouseholdRepository(localDb),
            clock,
            scope,
            ticks,
        )
    val uploader = PositionUploader(api, queue, controller, clock)

    /** The fake live socket: each `observeNow()` connection collects the flow [liveFlow] returns. */
    var liveFlow: () -> Flow<LiveMessage> = { kotlinx.coroutines.flow.emptyFlow() }
    var liveConnects = 0

    fun repository(timings: LocationTimings = FAST): LocationRepository =
        LocationRepository(
            api,
            localDb,
            MembersRepository(localDb),
            placesRepository,
            permissions,
            engine,
            scope,
            clock,
            controller.observe(),
            ticks,
            {
                liveConnects++
                liveFlow()
            },
            timings,
        )

    fun setNow(now: Instant) {
        clock.now = now
        ticks.value = now
    }

    suspend fun family() {
        tokens.save(Tokens("access", clock.now() + 1000.hours, "refresh", clock.now() + 1000.hours, "session"))
        localDb.transaction {
            householdQueries.upsert("h", "Петровы", "Europe/Moscow", "RUB", "ru")
            personQueries.upsert(P_OLGA, "Ольга", null, null, null, null, null, "[]", "red")
            personQueries.upsert(P_DIMA, "Дмитрий", null, null, null, null, null, "[]", "blue")
            personQueries.upsert(P_ANYA, "Аня", null, null, null, null, null, "[]", "green")
            memberQueries.upsert(OLGA, P_OLGA, null, null, 0)
            memberQueries.upsert(DIMA, P_DIMA, null, null, 0)
            memberQueries.upsert(ANYA, P_ANYA, null, null, 0)
        }
    }

    suspend fun loginAs(
        memberId: String,
        vararg permissions: Permission,
    ) = localDb.transaction {
        val person = mapOf(OLGA to P_OLGA, DIMA to P_DIMA, ANYA to P_ANYA).getValue(memberId)
        LocalSession.save(
            this,
            Me("u", memberId, "h", "Петровы", person, "Me", "me", true, isAdmin = false, permissions = permissions.toSet()),
        )
    }

    /** Places as if they had come from the server. */
    suspend fun putPlaces() =
        localDb.transaction {
            val adapter = adapters[EntityTypes.PLACE] as PlaceAdapter
            adapter.upsertTyped(this, HOME_ID, HOME)
            adapter.upsertTyped(this, SCHOOL_ID, SCHOOL)
        }

    suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    suspend fun pref(key: String) = localDb.read { location_prefQueries.get(key).executeAsOneOrNull() }

    fun close() = scope.cancel()

    private fun CoroutineScope.cancel() = (coroutineContext[Job] as Job).cancel()

    companion object {
        val FAST = LocationTimings(10.milliseconds, 40.milliseconds, 50.milliseconds, cacheInterval = 30_000.milliseconds)
    }
}

fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun <T> MockRequestHandleScope.json(
    serializer: KSerializer<T>,
    value: T,
) = json(LaresJson.encodeToString(serializer, value))

fun MockRequestHandleScope.apiError(
    status: HttpStatusCode,
    code: ErrorCode,
) = json(LaresJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, "test"))), status)

/** The JSON body of a request. */
fun HttpRequestData.bodyText(): String = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

fun fixAt(
    at: Instant,
    lat: Double = HOME.latitude,
    lon: Double = HOME.longitude,
    accuracy: Float = 10f,
    speed: Float? = null,
) = PositionFix(at, lat, lon, accuracy, speedMps = speed)
