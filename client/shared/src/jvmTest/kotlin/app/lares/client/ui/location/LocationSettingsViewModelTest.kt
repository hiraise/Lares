package app.lares.client.ui.location

import app.lares.client.data.MembersRepository
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.FakeLocationDevice
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.LOC_ADULT
import app.lares.client.data.location.LOC_KID
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.PauseChoice
import app.lares.client.data.location.SCHOOL_ID
import app.lares.client.data.location.bodyText
import app.lares.client.data.location.fixAt
import app.lares.client.data.location.json
import app.lares.client.ui.awaitState
import app.lares.client.ui.common.UiError
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.AlertWatcher
import app.lares.contract.api.location.LocationWatchers
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.api.location.Watcher
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Settings › Геопозиция (plan M4-07, LOC-010, LOC-008). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSettingsViewModelTest {
    private val device = FakeLocationDevice()
    private val f = LocationFixture(device = device)
    private var online = true

    @BeforeTest
    fun main() = Dispatchers.setMain(Dispatchers.Default)

    private val vms = TestViewModels()

    @AfterTest
    fun close() {
        // Clear the view models first: a late `WhileSubscribed` stop on a reset Main fails the next UI test.
        vms.clear()
        f.close()
        Dispatchers.resetMain()
    }

    private fun setUp(
        member: String = DIMA,
        kid: Boolean = false,
    ): LocationSettingsViewModel =
        runBlocking {
            f.family()
            f.loginAs(member, *(if (kid) LOC_KID else LOC_ADULT))
            f.server = { request ->
                if (!online) throw IOException("no network")
                when (request.url.encodedPath) {
                    "/api/v1/location/positions" -> {
                        json(UploadPositionsResponse.serializer(), UploadPositionsResponse(1, sharingHere = true))
                    }

                    "/api/v1/location/me/watchers" -> {
                        json(
                            LocationWatchers.serializer(),
                            LocationWatchers(
                                listOf(Watcher(OLGA, VisibilityLevel.HISTORY), Watcher(ANYA, VisibilityLevel.CURRENT)),
                                listOf(AlertWatcher(OLGA, setOf(SCHOOL_ID, HOME_ID, "gone-place"))),
                            ),
                        )
                    }

                    else -> {
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
            f.putPlaces()
            vms
                .keep(
                    LocationSettingsViewModel(
                        f.controller,
                        device,
                        f.permissions,
                        f.repository(),
                        MembersRepository(f.localDb),
                        f.placesRepository,
                    ),
                ).also { it.onResume() }
        }

    /** Waits for a condition that no state flow announces (requests sent in the background). */
    private fun eventually(condition: () -> Boolean) =
        runBlocking {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        }

    private fun sharingRequests(): List<SetSharingRequest> =
        synchronized(f.requests) {
            f.requests
                .filter { it.method == HttpMethod.Put && it.url.encodedPath == "/api/v1/location/me/sharing" }
                .map { LaresJson.decodeFromString(SetSharingRequest.serializer(), it.bodyText()) }
        }

    @Test
    fun `LOC-010 status texts for every state of this phone`() {
        val all = FakeLocationDevice.ALL_ALLOWED
        assertEquals(SharingStatusText.OFF, statusText(MySharing.Off, all))
        assertEquals(SharingStatusText.SHARING, statusText(MySharing.Sharing, all))
        assertEquals(SharingStatusText.PAUSED, statusText(MySharing.Paused(null), all))
        assertEquals(SharingStatusText.PAUSED_UNTIL, statusText(MySharing.Paused(Instant.parse("2026-10-21T11:00:00Z")), all))
        assertEquals(SharingStatusText.NEEDS_PERMISSION, statusText(MySharing.NeedsPermission, all.copy(background = false)))
        assertEquals(SharingStatusText.LOCATION_OFF, statusText(MySharing.NeedsPermission, all.copy(locationOn = false)))
        assertEquals(SharingStatusText.STALE, statusText(MySharing.StaleUploads, all))
    }

    @Test
    fun `LOC-010 the screen follows the controller - off, sharing, paused, needs permission, stale`() {
        val vm = setUp()
        awaitState(vm.state) { it.loaded && it.status == SharingStatusText.OFF }
        runBlocking { f.controller.enable() }
        awaitState(vm.state) { it.status == SharingStatusText.SHARING }

        runBlocking { f.controller.pause(PauseChoice.FOREVER) }
        awaitState(vm.state) { it.status == SharingStatusText.PAUSED && it.showResume && !it.showPause }
        runBlocking { f.controller.resume() }

        // Location switched off in the shade (no resume of the screen): seen at the next sharing update.
        device.current = FakeLocationDevice.ALL_ALLOWED.copy(locationOn = false)
        f.setNow(f.clock.now() + 1.minutes)
        awaitState(vm.state) { it.status == SharingStatusText.LOCATION_OFF }
        device.current = FakeLocationDevice.ALL_ALLOWED
        f.setNow(f.clock.now() + 1.minutes)
        awaitState(vm.state) { it.status == SharingStatusText.SHARING }

        // Permission taken away in the phone settings: seen when the screen is visible again.
        device.current = FakeLocationDevice.ALL_ALLOWED.copy(background = false)
        vm.onResume()
        awaitState(vm.state) { it.status == SharingStatusText.NEEDS_PERMISSION }
        device.current = FakeLocationDevice.ALL_ALLOWED.copy(locationOn = false)
        vm.onResume()
        awaitState(vm.state) { it.status == SharingStatusText.LOCATION_OFF }
        vm.openLocationSettings()
        assertEquals(listOf("locationSettings"), device.calls)
        device.current = FakeLocationDevice.ALL_ALLOWED
        vm.onResume()

        // LOC-008: nothing uploaded for an hour while fixes wait.
        runBlocking {
            f.controller.onUpload(UploadPositionsResponse(1, sharingHere = true))
            f.queue.add(fixAt(f.clock.now()))
        }
        f.setNow(f.clock.now() + 61.minutes)
        awaitState(vm.state) { it.status == SharingStatusText.STALE && it.showPause }
    }

    @Test
    fun `LOC-010 pause choices go to the controller, resume ends the pause`() {
        val vm = setUp()
        runBlocking { f.controller.enable() }
        awaitState(vm.state) { it.status == SharingStatusText.SHARING && it.showPause && !it.showNoPauseNote }
        vm.openPause()
        awaitState(vm.state) { it.pauseOpen }
        val now = f.clock.now()

        vm.pause(PauseChoice.HOUR)
        awaitState(vm.state) { !it.pauseOpen && it.status == SharingStatusText.PAUSED_UNTIL && it.pausedUntil == now + 1.hours }
        vm.resume()
        awaitState(vm.state) { it.status == SharingStatusText.SHARING }
        vm.pause(PauseChoice.MORNING)
        // 13:00 in Moscow: the next 08:00 is tomorrow.
        awaitState(vm.state) { it.pausedUntil == Instant.parse("2026-10-22T05:00:00Z") }
        vm.pause(PauseChoice.FOREVER)
        awaitState(vm.state) { it.status == SharingStatusText.PAUSED }

        eventually { sharingRequests().size == 5 }
        assertEquals(
            listOf(SharingStatus.SHARING, SharingStatus.PAUSED, SharingStatus.SHARING, SharingStatus.PAUSED, SharingStatus.PAUSED),
            sharingRequests().map { it.status },
        )
        assertEquals(
            listOf(now + 1.hours, null, Instant.parse("2026-10-22T05:00:00Z"), null),
            sharingRequests().drop(1).map { it.pausedUntil },
        )
    }

    @Test
    fun `LOC-010 a kid has no pause, only the note`() {
        val vm = setUp(ANYA, kid = true)
        runBlocking { f.controller.enable() }
        val state = awaitState(vm.state) { it.loaded && it.status == SharingStatusText.SHARING }
        assertFalse(state.canPause)
        assertFalse(state.showPause)
        assertTrue(state.showNoPauseNote)
    }

    @Test
    fun `LOC-001 the setup enables online, offline it stays open with the error`() {
        val vm = setUp()
        vm.openSetup()
        val open = awaitState(vm.state) { it.setupOpen && it.watchers != null }
        assertEquals(listOf("Аня", "Ольга"), open.watchers)

        online = false
        vm.enable()
        val failed = awaitState(vm.state) { !it.enabling && it.enableError != null }
        assertTrue(failed.setupOpen)
        assertEquals(UiError.Network, failed.enableError)
        assertTrue("start" !in f.tracking.calls)

        online = true
        vm.enable()
        awaitState(vm.state) { !it.setupOpen && it.status == SharingStatusText.SHARING }
        assertEquals(listOf("start"), f.tracking.calls)
    }

    @Test
    fun `LOC-015 who sees me and who gets alerts about me, unavailable offline`() {
        val vm = setUp()
        vm.loadTransparency()
        val loaded = awaitState(vm.state) { it.transparency is Transparency.Loaded }.transparency as Transparency.Loaded
        assertEquals(listOf(WatcherRow("Аня", history = false), WatcherRow("Ольга", history = true)), loaded.viewers)
        assertEquals(listOf(AlertWatcherRow("Ольга", listOf("Дом", "Школа №12"))), loaded.alerts, "by name; unknown places left out")

        online = false
        val offline = setUp()
        offline.loadTransparency()
        awaitState(offline.state) { it.transparency == Transparency.Unavailable }
    }
}
