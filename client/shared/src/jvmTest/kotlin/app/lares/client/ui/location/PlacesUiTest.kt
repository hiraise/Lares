package app.lares.client.ui.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.Place
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.SCHOOL_ID
import app.lares.client.data.location.fixAt
import app.lares.client.ui.common.raw
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.api.location.AddressResult
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.location.Stop
import kotlinx.datetime.LocalDate
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/** Places sheet, place sheet, place editor and history with the JVM map stand-in (plan M4-09). */
@OptIn(ExperimentalTestApi::class)
class PlacesUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val config = LocationConfig("light", "dark", addresses = true, historyDays = 30)
    private val calls = mutableListOf<String>()
    private val home = Place(HOME_ID, HOME)
    private val school = Place(SCHOOL_ID, SCHOOL.copy(address = "ул. Пушкина, 3"))

    private fun member(
        id: String,
        name: String,
        place: Place?,
    ) = MemberOnMap(
        id,
        null,
        name,
        PaletteColor.BLUE,
        VisibilityLevel.HISTORY,
        SharingStatus.SHARING,
        null,
        null,
        fixAt(Clock.System.now()),
        place,
        null,
        stale = false,
        moving = false,
    )

    private val members = listOf(member(ANYA, "Аня", school), member(OLGA, "Тимур", school), member(DIMA, "Дмитрий", null))

    private val actions =
        MapActions(
            onBack = {},
            onSelect = {},
            onCloseCard = {},
            onRefresh = {},
            onLocate = {},
            onPlaces = {},
            onClosePlaces = { calls += "closePlaces" },
            onPlace = { calls += "place $it" },
            onSetup = {},
            onTurnOnLocation = {},
            onPause = {},
            onResume = {},
            onEditPlace = { calls += "edit $it" },
            onDeletePlace = { calls += "delete $it" },
            onAddPlace = { calls += "add" },
            onHistory = { calls += "history $it" },
        )

    private fun mapState(
        canManage: Boolean,
        placesOpen: Boolean = false,
        place: Place? = null,
    ) = MapUiState(
        loaded = true,
        me = DIMA,
        members = members,
        places = listOf(home, school),
        config = config,
        placesOpen = placesOpen,
        place = place,
        canManagePlaces = canManage,
    )

    private val sharing = LocationSettingsUiState(loaded = true, sharing = MySharing.Sharing)

    @Test
    fun `LOC-020 the places sheet lists who is where, managers can add`() =
        runComposeUiTest {
            var state by mutableStateOf(mapState(canManage = true, placesOpen = true))
            setContent { LaresTheme { MapScreen(state, sharing, actions) } }
            onNodeWithTag("places_sheet").assertIsDisplayed()
            onNodeWithText("Аня, Тимур are here").assertIsDisplayed()
            onNodeWithText("Nobody").assertIsDisplayed()
            onNodeWithText("Add place").performClick()
            onNodeWithTag("place_row_$SCHOOL_ID").performClick()
            assertEquals(listOf("add", "place $SCHOOL_ID"), calls)
            state = mapState(canManage = false, placesOpen = true)
            onNodeWithText("Add place").assertDoesNotExist()
        }

    @Test
    fun `LOC-020 the place sheet shows the address and who is here, edit and delete for managers`() =
        runComposeUiTest {
            var state by mutableStateOf(mapState(canManage = true, place = school))
            setContent { LaresTheme { MapScreen(state, sharing, actions) } }
            onNodeWithTag("place_sheet").assertIsDisplayed()
            onNodeWithText("ул. Пушкина, 3").assertIsDisplayed()
            onNodeWithText("Аня, Тимур are here").assertIsDisplayed()
            onNodeWithTag("place_edit").performClick()
            onNodeWithTag("place_delete").performClick()
            onNodeWithText("Delete place «Школа №12»?").assertIsDisplayed()
            onNodeWithText("Delete").performClick()
            assertEquals(listOf("edit $SCHOOL_ID", "delete $SCHOOL_ID"), calls)
            state = mapState(canManage = false, place = school)
            onNodeWithTag("place_edit").assertDoesNotExist()
        }

    @Test
    fun `map circles open the place, the card offers history`() =
        runComposeUiTest {
            val row = MemberRow(members[0], "Школа №12", null)
            val card = MemberCard(row, accuracyM = 10, canRefresh = false, refresh = null, canHistory = true)
            setContent { LaresTheme { MapScreen(mapState(canManage = false).copy(card = card), sharing, actions) } }
            onNodeWithTag("place_$HOME_ID").performClick()
            onNodeWithText("History").performClick()
            assertEquals(listOf("place $HOME_ID", "history $ANYA"), calls)
        }

    @Test
    fun `LOC-020 LOC-024 the editor shows name, icons, here, radius and the search results`() =
        runComposeUiTest {
            val form =
                PlaceForm
                    .new(55.8, 37.5)
                    .withName("Basseyn")
                    .withIcon(PlaceIcon.SPORT)
                    .copy(radiusM = 150)
            val results = listOf(AddressResult("Ленинградский проспект, 39", 55.79, 37.54))
            val state =
                PlaceEditorUiState(
                    loaded = true,
                    form = form,
                    initial = PlaceForm.new(55.8, 37.5),
                    canManage = true,
                    config = config,
                    here = fixAt(Instant.parse("2026-10-21T10:00:00Z")),
                    search = AddressSearchUi("Leningradsky prospekt 39", results = results),
                )
            val editor =
                PlaceEditorActions(
                    onIcon = { calls += "icon $it" },
                    onHere = { calls += "here" },
                    onSearch = { calls += "search" },
                    onPick = { calls += "pick ${it.label}" },
                    onSave = { calls += "save" },
                )
            setContent { LaresTheme { PlaceEditorScreen(state, editor) } }
            onNodeWithText("New place").assertIsDisplayed()
            onNodeWithText("Basseyn 150").assertExists()
            onNodeWithText("150 m").assertIsDisplayed()
            onNodeWithTag("icon_school").performClick()
            onNodeWithText("I'm here").performScrollTo().performClick()
            onNodeWithText("Find").performScrollTo().performClick()
            onNodeWithText("Ленинградский проспект, 39").performScrollTo().performClick()
            onNodeWithTag("place_save").performScrollTo().performClick()
            assertEquals(listOf("icon SCHOOL", "here", "search", "pick Ленинградский проспект, 39", "save"), calls)
        }

    @Test
    fun `LOC-020 without the permission the editor is read-only, without addresses no search`() =
        runComposeUiTest {
            var state by mutableStateOf(PlaceEditorUiState(loaded = true, canManage = false, config = config))
            setContent { LaresTheme { PlaceEditorScreen(state, PlaceEditorActions()) } }
            onNodeWithText("Only members who may manage places can change them.").assertIsDisplayed()
            onNodeWithTag("place_save").assertDoesNotExist()
            state = PlaceEditorUiState(loaded = true, canManage = true, config = config.copy(addresses = false))
            onNodeWithTag("place_save").assertExists()
            onNodeWithText("Find").assertDoesNotExist()
            onNodeWithText("I'm here").assertDoesNotExist()
        }

    @Test
    fun `LOC-004 history shows the day strip and the stops, a tap centres a stop`() =
        runComposeUiTest {
            val today = LocalDate(2026, 10, 21)
            val stop = Stop(Instant.parse("2026-10-21T05:25:00Z"), Instant.parse("2026-10-21T11:40:00Z"), 55.8, 37.53, SCHOOL_ID, false)
            var state by
                mutableStateOf(
                    HistoryUiState(
                        name = "Аня",
                        today = today,
                        days = HistoryLogic.days(today, 7),
                        selected = today,
                        phase = HistoryPhase.READY,
                        rows = listOf(StopRow("stop_0", stop, raw("Школа №12"), raw("8:25–14:40"), home = false)),
                        config = config,
                    ),
                )
            setContent { LaresTheme { HistoryScreen(state, {}, { calls += "day $it" }, { calls += "stop $it" }) } }
            onNodeWithText("Аня · History").assertIsDisplayed()
            onNodeWithText("Today").assertIsDisplayed()
            onNodeWithText("Yesterday").performClick()
            onNodeWithText("Школа №12").assertIsDisplayed()
            onNodeWithText("8:25–14:40").performClick()
            onNodeWithTag("map_stop_0").assertExists()
            assertEquals(listOf("day 2026-10-20", "stop stop_0"), calls)
            state = state.copy(phase = HistoryPhase.EMPTY, rows = emptyList())
            onNodeWithText("No data for this day").assertIsDisplayed()
            state = state.copy(phase = HistoryPhase.OFFLINE)
            onNodeWithText("History is available when there is a connection").assertIsDisplayed()
        }
}
