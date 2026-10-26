package app.lares.client.ui.location

import app.lares.client.data.HouseholdRepository
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.EnglishLocationTexts
import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.LOC_ADULT
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.LocationTexts
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.apiError
import app.lares.client.data.location.fixAt
import app.lares.client.data.location.json
import app.lares.client.ui.awaitState
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.AddressLabel
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Map's view model (plan M4-08, LOC-011, LOC-014). */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val f = LocationFixture()
    private val now: Instant = f.clock.now()
    private val socket = Channel<LiveMessage>(Channel.UNLIMITED)
    private val addressCalls = mutableListOf<String>()
    private var refreshAnswer: ErrorCode? = null

    @BeforeTest
    fun main() = Dispatchers.setMain(Dispatchers.Default)

    private val vms = TestViewModels()

    @AfterTest
    fun close() {
        vms.clear()
        f.close()
        Dispatchers.resetMain()
    }

    private fun member(
        id: String,
        at: Instant = now - 1.minutes,
        lat: Double = HOME.latitude - 0.01,
        status: SharingStatus = SharingStatus.SHARING,
        placeId: String? = null,
        speed: Float? = null,
    ) = MemberLocation(
        memberId = id,
        level = VisibilityLevel.HISTORY,
        status = status,
        last = fixAt(at, lat = lat, speed = speed),
        placeId = placeId,
        placeSince = placeId?.let { now - 40.minutes },
    )

    private fun snapshot(vararg members: MemberLocation) = LiveMessage(LiveMessage.SNAPSHOT, now = LocationNow(members.toList(), now))

    private fun setUp(timeout: Duration = 90.seconds): MapViewModel {
        runBlocking {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
        }
        f.liveFlow = { socket.receiveAsFlow() }
        f.server = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v1/location/config" -> {
                    json(LocationConfig.serializer(), LocationConfig("light", "dark", addresses = true, historyDays = 30))
                }

                path == "/api/v1/location/address" -> {
                    synchronized(addressCalls) { addressCalls += request.url.parameters["lat"].orEmpty() }
                    json(AddressLabel.serializer(), AddressLabel("ул. Ленина, 12"))
                }

                path.endsWith("/refresh") -> {
                    refreshAnswer?.let { apiError(HttpStatusCode.Conflict, it) } ?: respond("", HttpStatusCode.NoContent)
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
        return vms.keep(
            MapViewModel(
                f.repository(),
                LocationTexts(EnglishLocationTexts()),
                HouseholdRepository(f.localDb),
                f.localDb,
                f.permissions,
                f.placesWriter,
                f.clock,
                MapArgs(),
                timeout,
            ),
        )
    }

    @Test
    fun `LOC-011 refresh waits, then done when a newer position arrives`() {
        val vm = setUp()
        socket.trySend(snapshot(member(ANYA), member(DIMA)))
        awaitState(vm.state) { it.rows.size == 1 && it.config != null }
        vm.select(ANYA)
        val card = awaitState(vm.state) { it.card != null }.card!!
        assertEquals(true, card.canRefresh)
        vm.refresh(ANYA)
        assertEquals(RefreshPhase.WAITING, awaitState(vm.state) { it.card?.refresh != null }.card!!.refresh!!.phase)
        socket.trySend(LiveMessage(LiveMessage.MEMBER, member = member(ANYA, at = now)))
        awaitState(vm.state) { it.card?.refresh?.phase == RefreshPhase.DONE }
        // The call may still be on its way when the newer fix arrives.
        val refreshCalls =
            runBlocking {
                withTimeout(5.seconds) {
                    var calls: List<String>
                    do {
                        calls = synchronized(f.requests) { f.requests.map { it.url.encodedPath } }.filter { it.endsWith("/refresh") }
                        if (calls.isEmpty()) delay(20)
                    } while (calls.isEmpty())
                    calls
                }
            }
        assertEquals(listOf("/api/v1/location/members/$ANYA/refresh"), refreshCalls)
    }

    @Test
    fun `LOC-011 no newer position in time - the phone did not answer`() {
        val vm = setUp(timeout = 300.milliseconds)
        socket.trySend(snapshot(member(ANYA)))
        awaitState(vm.state) { it.rows.size == 1 }
        vm.select(ANYA)
        awaitState(vm.state) { it.card != null }
        vm.refresh(ANYA)
        awaitState(vm.state) { it.card?.refresh?.phase == RefreshPhase.NO_ANSWER }
    }

    @Test
    fun `LOC-011 CONFLICT - the member is paused`() {
        val vm = setUp()
        refreshAnswer = ErrorCode.CONFLICT
        socket.trySend(snapshot(member(ANYA)))
        awaitState(vm.state) { it.rows.size == 1 }
        vm.select(ANYA)
        awaitState(vm.state) { it.card != null }
        vm.refresh(ANYA)
        awaitState(vm.state) { it.card?.refresh?.phase == RefreshPhase.PAUSED }
    }

    @Test
    fun `LOC-014 addresses once per rounded position, never in a place or moving`() {
        val vm = setUp()
        socket.trySend(
            snapshot(
                member(ANYA),
                member(OLGA, lat = HOME.latitude - 0.02, speed = 3f, at = now),
                member(DIMA, lat = HOME.latitude, placeId = HOME_ID),
            ),
        )
        val shown = awaitState(vm.state) { s -> s.rows.any { it.status.startsWith("ул. Ленина") } }
        assertEquals(listOf("ул. Ленина, 12 · now", "On the way · now"), shown.rows.map { it.status })
        // The same position rounded again, and a minute later: no new lookup.
        socket.trySend(LiveMessage(LiveMessage.MEMBER, member = member(ANYA, lat = HOME.latitude - 0.01 + 0.000001)))
        f.setNow(now + 1.minutes)
        awaitState(vm.state) { s -> s.rows.any { it.status == "ул. Ленина, 12 · 2 min ago" } }
        assertEquals(1, synchronized(addressCalls) { addressCalls.size })
    }

    @Test
    fun `LOC-003 the card follows the selection and back closes it, the camera goes to the member`() {
        val vm = setUp()
        socket.trySend(snapshot(member(ANYA), member(DIMA)))
        val first = awaitState(vm.state) { it.rows.size == 1 && it.camera != null }
        assertEquals(CameraKind.All, first.camera!!.kind)
        assertEquals(CameraKind.Member(DIMA), first.nextLocate)
        vm.select(ANYA)
        val selected = awaitState(vm.state) { it.card != null }
        assertEquals(CameraKind.Member(ANYA), selected.camera!!.kind)
        assertNotNull(selected.card!!.accuracyM)
        vm.closeCard()
        assertNull(awaitState(vm.state) { it.card == null }.card)
        vm.locate()
        assertEquals(CameraKind.All, awaitState(vm.state) { it.camera?.kind == CameraKind.All }.camera!!.kind)
        vm.locate()
        awaitState(vm.state) { it.camera?.kind == CameraKind.Member(DIMA) }
    }

    @Test
    fun `places - a circle opens the sheet, managers delete, the card offers history`() {
        val vm = setUp()
        socket.trySend(snapshot(member(ANYA, placeId = HOME_ID)))
        val loaded = awaitState(vm.state) { it.rows.size == 1 }
        assertEquals(true, loaded.canManagePlaces)
        vm.select(ANYA)
        assertEquals(true, awaitState(vm.state) { it.card != null }.card!!.canHistory)
        vm.openPlace(HOME_ID)
        val open = awaitState(vm.state) { it.place != null }
        assertEquals(HOME_ID, open.place!!.id)
        assertNull(open.card)
        assertEquals(CameraKind.Place(HOME_ID), open.camera!!.kind)
        vm.deletePlace(HOME_ID)
        awaitState(vm.state) { it.place == null && it.places.none { p -> p.id == HOME_ID } }
        assertEquals("delete", runBlocking { f.outbox() }.single().op.lowercase())
    }
}
