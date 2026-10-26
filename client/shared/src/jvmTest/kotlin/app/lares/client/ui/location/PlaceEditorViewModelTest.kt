package app.lares.client.ui.location

import app.lares.client.data.location.DIMA
import app.lares.client.data.location.LOC_ADULT
import app.lares.client.data.location.LOC_KID
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.SCHOOL_ID
import app.lares.client.data.location.fixAt
import app.lares.client.data.location.json
import app.lares.client.ui.awaitState
import app.lares.contract.api.location.AddressResult
import app.lares.contract.api.location.AddressSearch
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The place editor's view model (plan M4-09, LOC-020, LOC-024). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceEditorViewModelTest {
    private val f = LocationFixture()
    private var addresses = true
    private var offline = false
    private val searches = mutableListOf<String>()

    @BeforeTest
    fun main() = Dispatchers.setMain(Dispatchers.Default)

    private val vms = TestViewModels()

    @AfterTest
    fun close() {
        vms.clear()
        f.close()
        Dispatchers.resetMain()
    }

    private fun result(n: Int) = AddressResult("Ленинградский проспект, 3$n", 55.79 + n / 1000.0, 37.54)

    private fun setUp(
        args: PlaceEditorArgs = PlaceEditorArgs(lat = 55.8, lon = 37.5),
        kid: Boolean = false,
    ): PlaceEditorViewModel {
        runBlocking {
            f.family()
            f.putPlaces()
            if (kid) f.loginAs(DIMA, *LOC_KID) else f.loginAs(DIMA, *LOC_ADULT)
        }
        // The viewer's own position comes over the live socket.
        f.liveFlow = {
            flowOf(
                LiveMessage(
                    LiveMessage.SNAPSHOT,
                    now =
                        LocationNow(
                            listOf(
                                MemberLocation(
                                    DIMA,
                                    VisibilityLevel.HISTORY,
                                    SharingStatus.SHARING,
                                    last = fixAt(f.clock.now(), 55.77, 37.61),
                                ),
                            ),
                            f.clock.now(),
                        ),
                ),
            ).onCompletion { awaitCancellation() }
        }
        f.server = { request ->
            if (offline) throw IOException("offline")
            when (request.url.encodedPath) {
                "/api/v1/location/config" -> {
                    json(LocationConfig.serializer(), LocationConfig("light", "dark", addresses = addresses, historyDays = 30))
                }

                "/api/v1/location/search" -> {
                    synchronized(searches) { searches += request.url.parameters["q"].orEmpty() }
                    json(AddressSearch.serializer(), AddressSearch((1..7).map(::result)))
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
        return vms.keep(PlaceEditorViewModel(f.repository(), f.placesRepository, f.placesWriter, f.localDb, f.permissions, args))
    }

    @Test
    fun `LOC-024 search runs only on submit, a result moves the centre and fills the address`() {
        val vm = setUp()
        val first = awaitState(vm.state) { it.loaded && it.searchAvailable }
        assertEquals(55.8 to 37.5, first.form.latitude to first.form.longitude)
        assertEquals(CameraKind.Point(55.8, 37.5, MEMBER_ZOOM), first.camera!!.kind)
        vm.onQuery("Leningradsky prospekt 39")
        Thread.sleep(200)
        assertTrue(synchronized(searches) { searches.isEmpty() })
        vm.search()
        val found = awaitState(vm.state) { it.search.results != null }
        assertEquals(listOf("Leningradsky prospekt 39"), synchronized(searches) { searches.toList() })
        assertEquals(5, found.search.results!!.size)
        vm.pick(found.search.results[1])
        val picked = awaitState(vm.state) { it.form.address != null }
        assertEquals(result(2).latitude to 37.54, picked.form.latitude to picked.form.longitude)
        assertEquals("Ленинградский проспект, 32", picked.form.address)
        assertNull(picked.search.results)
        assertEquals(CameraKind.Point(result(2).latitude, 37.54, MEMBER_ZOOM), picked.camera!!.kind)
    }

    @Test
    fun `LOC-024 search is hidden when the server has no addresses`() {
        addresses = false
        val vm = setUp()
        val state = awaitState(vm.state) { it.loaded && it.config != null }
        assertFalse(state.searchAvailable)
        vm.onQuery("Lenina 12")
        vm.search()
        Thread.sleep(200)
        assertTrue(synchronized(searches) { searches.isEmpty() })
    }

    @Test
    fun `LOC-020 a new place is saved offline with the picked icon, I am here from the own fix`() {
        val vm = setUp()
        awaitState(vm.state) { it.loaded && it.here != null }
        offline = true
        vm.onName("Бассейн")
        vm.onIcon(PlaceIcon.SPORT)
        vm.onRadius(147f)
        vm.useHere()
        val here = awaitState(vm.state) { it.form.latitude == 55.77 }
        assertEquals(37.61, here.form.longitude)
        vm.save()
        assertEquals(PlaceEditorResult.SAVED, awaitState(vm.state) { it.result != null }.result)
        val outbox = runBlocking { f.outbox() }.single { it.type == EntityTypes.PLACE }
        assertTrue(outbox.fields!!.contains("Бассейн") && outbox.fields.contains("\"sport\"") && outbox.fields.contains("150"))
    }

    @Test
    fun `LOC-020 editing sends only the changed field, delete removes the place`() {
        val vm = setUp(PlaceEditorArgs(id = SCHOOL_ID))
        val loaded = awaitState(vm.state) { it.loaded }
        assertEquals(SCHOOL.name, loaded.form.name)
        assertFalse(loaded.isNew)
        vm.onRadius(200f)
        vm.save()
        awaitState(vm.state) { it.result == PlaceEditorResult.SAVED }
        val sent = runBlocking { f.outbox() }.single { it.type == EntityTypes.PLACE }
        assertTrue(sent.fields!!.contains("radiusM") && !sent.fields.contains("name"))
        val again = setUp(PlaceEditorArgs(id = SCHOOL_ID))
        awaitState(again.state) { it.loaded }
        again.delete()
        assertEquals(PlaceEditorResult.DELETED, awaitState(again.state) { it.result != null }.result)
    }

    @Test
    fun `members without manage_places cannot save, a missing place closes`() {
        val kid = setUp(kid = true)
        val state = awaitState(kid.state) { it.loaded && it.config != null }
        assertFalse(state.canManage)
        assertFalse(state.searchAvailable)
        val gone = setUp(PlaceEditorArgs(id = "01928c4e-0000-7000-8000-0000000000ff"))
        assertEquals(PlaceEditorResult.GONE, awaitState(gone.state) { it.result != null }.result)
    }
}
