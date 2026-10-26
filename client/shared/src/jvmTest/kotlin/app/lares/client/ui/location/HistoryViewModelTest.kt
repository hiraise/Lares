package app.lares.client.ui.location

import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.HOME
import app.lares.client.data.location.LOC_ADULT
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.apiError
import app.lares.client.data.location.json
import app.lares.client.ui.awaitState
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.AddressLabel
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationHistory
import app.lares.contract.api.location.TrackPoint
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The history screen's view model (plan M4-09, LOC-004). */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val f = LocationFixture()
    private val today = LocalDate(2026, 10, 21)
    private var history: (LocalDate) -> List<TrackPoint> = { emptyList() }
    private var historyError: ErrorCode? = null
    private var offline = false
    private val asked = mutableListOf<String>()

    @BeforeTest
    fun main() = Dispatchers.setMain(Dispatchers.Default)

    private val vms = TestViewModels()

    @AfterTest
    fun close() {
        vms.clear()
        f.close()
        Dispatchers.resetMain()
    }

    /** Points every [step] minutes from [from] to [to] (UTC) at one spot. */
    private fun stay(
        from: String,
        to: String,
        lat: Double,
        lon: Double,
        step: Int = 10,
    ): List<TrackPoint> {
        val start = Instant.parse("2026-10-21T$from:00Z")
        val end = Instant.parse("2026-10-21T$to:00Z")
        return generateSequence(start) { it + step.minutes }.takeWhile { it <= end }.map { TrackPoint(it, lat, lon, 10f) }.toList()
    }

    private fun setUp(date: LocalDate? = null): HistoryViewModel {
        runBlocking {
            f.family()
            f.putPlaces()
            f.loginAs(DIMA, *LOC_ADULT)
        }
        f.server = { request ->
            val path = request.url.encodedPath
            if (offline) throw IOException("offline")
            when (path) {
                "/api/v1/location/config" -> {
                    json(LocationConfig.serializer(), LocationConfig("light", "dark", addresses = true, historyDays = 14))
                }

                "/api/v1/location/history" -> {
                    synchronized(asked) { asked += request.url.parameters["date"].orEmpty() }
                    val day = LocalDate.parse(request.url.parameters["date"]!!)
                    historyError?.let { apiError(HttpStatusCode.Forbidden, it) }
                        ?: json(LocationHistory.serializer(), LocationHistory(ANYA, day, history(day)))
                }

                "/api/v1/location/address" -> {
                    json(AddressLabel.serializer(), AddressLabel("ул. Ленина, 12"))
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
        return vms.keep(
            HistoryViewModel(
                f.repository(),
                MembersRepository(f.localDb),
                f.placesRepository,
                HouseholdRepository(f.localDb),
                f.clock,
                HistoryArgs(ANYA, date),
            ),
        )
    }

    @Test
    fun `LOC-004 today's track with stops, the day strip from the config`() {
        history = {
            stay("04:00", "05:10", HOME.latitude, HOME.longitude) +
                stay("05:25", "06:25", 55.78, 37.60) +
                stay("06:40", "09:50", SCHOOL.latitude, SCHOOL.longitude, step = 15)
        }
        val vm = setUp()
        val state = awaitState(vm.state) { it.phase == HistoryPhase.READY && it.rows.size == 3 && it.name.isNotEmpty() }
        assertEquals("Аня", state.name)
        assertEquals(14, state.days.size)
        assertEquals(today, state.selected)
        assertTrue(state.track.size > 10)
        assertTrue(state.camera?.kind is CameraKind.Bounds)
        assertEquals(listOf(false, false, true), state.rows.map { it.stop.ongoing })
        // The stop outside places gets its street address.
        awaitState(vm.state) { s ->
            s.rows[1]
                .title
                .toString()
                .contains("ул. Ленина, 12")
        }
        vm.showStop("stop_2")
        assertEquals(
            CameraKind.Point(state.rows[2].stop.latitude, state.rows[2].stop.longitude, MEMBER_ZOOM),
            awaitState(vm.state) { it.camera?.kind is CameraKind.Point }.camera!!.kind,
        )
    }

    @Test
    fun `an empty day says so, another day is loaded on a tap`() {
        val vm = setUp()
        awaitState(vm.state) { it.phase == HistoryPhase.EMPTY }
        history = { stay("06:00", "07:00", HOME.latitude, HOME.longitude) }
        vm.select(LocalDate(2026, 10, 19))
        val state = awaitState(vm.state) { it.phase == HistoryPhase.READY }
        assertEquals(LocalDate(2026, 10, 19), state.selected)
        assertEquals(listOf("2026-10-21", "2026-10-19"), synchronized(asked) { asked.toList() })
    }

    @Test
    fun `a day outside the strip opens today`() {
        val vm = setUp(LocalDate(2026, 9, 1))
        assertEquals(today, awaitState(vm.state) { it.phase == HistoryPhase.EMPTY }.selected)
    }

    @Test
    fun `FORBIDDEN closes the screen`() {
        historyError = ErrorCode.FORBIDDEN
        val vm = setUp()
        awaitState(vm.state) { it.denied }
    }

    @Test
    fun `offline - history is available with a connection`() {
        offline = true
        val vm = setUp()
        val state = awaitState(vm.state) { it.phase == HistoryPhase.OFFLINE }
        assertEquals(30, state.days.size)
    }
}
