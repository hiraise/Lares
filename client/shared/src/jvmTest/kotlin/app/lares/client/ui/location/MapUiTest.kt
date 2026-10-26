package app.lares.client.ui.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.Connection
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.HOME
import app.lares.client.data.location.HOME_ID
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.Place
import app.lares.client.data.location.fixAt
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** The Map screen with the JVM map stand-in (plan M4-08, LOC-003, LOC-009, LOC-011). */
@OptIn(ExperimentalTestApi::class)
class MapUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val now = Clock.System.now()
    private val calls = mutableListOf<String>()
    private val actions =
        MapActions(
            onBack = { calls += "back" },
            onSelect = { calls += "select $it" },
            onCloseCard = { calls += "close" },
            onRefresh = { calls += "refresh $it" },
            onLocate = { calls += "locate" },
            onPlaces = { calls += "places" },
            onClosePlaces = { calls += "closePlaces" },
            onPlace = { calls += "place $it" },
            onSetup = { calls += "setup" },
            onTurnOnLocation = { calls += "location" },
            onPause = { calls += "pause" },
            onResume = { calls += "resume" },
        )

    private fun member(
        id: String,
        name: String,
        status: SharingStatus = SharingStatus.SHARING,
        color: PaletteColor = PaletteColor.BLUE,
    ) = MemberOnMap(
        id,
        null,
        name,
        color,
        VisibilityLevel.HISTORY,
        status,
        null,
        null,
        fixAt(now - 3.minutes, accuracy = 20f).copy(battery = 38),
        null,
        null,
        stale = false,
        moving = false,
    )

    private val anya = member(ANYA, "Аня", color = PaletteColor.PURPLE)
    private val olga = member(OLGA, "Ольга", status = SharingStatus.PAUSED, color = PaletteColor.RED)
    private val dima = member(DIMA, "Дмитрий")

    private fun state(
        connection: Connection = Connection.LIVE,
        card: MemberCard? = null,
    ) = MapUiState(
        loaded = true,
        me = DIMA,
        members = listOf(anya, dima, olga),
        places = listOf(Place(HOME_ID, HOME)),
        rows = listOf(MemberRow(anya, "Школа №12 · 3 min ago", MapLogic.battery(anya.last)), MemberRow(olga, "Paused until 15:30", null)),
        connection = connection,
        config = LocationConfig("light", "dark", addresses = true, historyDays = 30),
        card = card,
    )

    private val sharingOn = LocationSettingsUiState(loaded = true, sharing = MySharing.Sharing, canPause = true)

    @Test
    fun `LOC-003 the sheet lists the others with status and battery, pins for everybody, a tap selects`() =
        runComposeUiTest {
            setContent { LaresTheme { MapScreen(state(), sharingOn, actions) } }
            onNodeWithText("You are sharing your location").assertIsDisplayed()
            onNodeWithText("Аня").assertIsDisplayed()
            onNodeWithText("Школа №12 · 3 min ago").assertIsDisplayed()
            onNodeWithText("38%").assertIsDisplayed()
            onNodeWithText("Paused until 15:30").assertIsDisplayed()
            onNodeWithText("Дмитрий").assertDoesNotExist()
            onNodeWithTag("pin_$DIMA").assertExists()
            onNodeWithText("О (grey)").assertExists()
            onNodeWithTag("offline_banner").assertDoesNotExist()
            onNodeWithTag("member_$ANYA").performClick()
            onNodeWithTag("pin_$OLGA").performClick()
            onNodeWithText("Pause").performClick()
            onNodeWithTag("locate").performClick()
            assertEquals(listOf("select $ANYA", "select $OLGA", "pause", "locate"), calls)
        }

    @Test
    fun `LOC-011 the card shows accuracy and battery, refresh only when allowed`() =
        runComposeUiTest {
            val sharingCard = MemberCard(state().rows[0], accuracyM = 20, canRefresh = true, refresh = null)
            var current by mutableStateOf(state(card = sharingCard))
            setContent { LaresTheme { MapScreen(current, sharingOn, actions) } }
            onNodeWithTag("member_card").assertIsDisplayed()
            onNodeWithText("Accuracy 20 m").assertIsDisplayed()
            onNodeWithText("Refresh").performClick()
            current = state(card = sharingCard.copy(refresh = RefreshUi(ANYA, RefreshPhase.NO_ANSWER, null)))
            onNodeWithText("The phone did not answer").assertIsDisplayed()
            current = state(card = MemberCard(state().rows[1], accuracyM = 20, canRefresh = false, refresh = null))
            onNodeWithText("Refresh").assertDoesNotExist()
            onNodeWithTag("card_back").performClick()
            assertEquals(listOf("refresh $ANYA", "close"), calls)
        }

    @Test
    fun `LOC-009 offline - the banner, the last positions still listed`() =
        runComposeUiTest {
            setContent { LaresTheme { MapScreen(state(Connection.OFFLINE), sharingOn, actions) } }
            onNodeWithText("No connection. Showing the last known places.").assertIsDisplayed()
            onNodeWithText("Аня").assertIsDisplayed()
        }

    @Test
    fun `LOC-009 without a style the map waits for the network`() =
        runComposeUiTest {
            setContent { LaresTheme { MapScreen(state().copy(config = null), sharingOn, actions) } }
            onNodeWithText("The map appears when there is a connection").assertIsDisplayed()
        }

    @Test
    fun `sharing row - off offers to turn on, paused offers resume`() =
        runComposeUiTest {
            var sharing by mutableStateOf(LocationSettingsUiState(loaded = true, sharing = MySharing.Off))
            setContent { LaresTheme { MapScreen(state(), sharing, actions) } }
            onNodeWithText("Turn on location sharing").performClick()
            sharing = LocationSettingsUiState(loaded = true, sharing = MySharing.Paused(null), canPause = true)
            onNodeWithText("Paused").assertIsDisplayed()
            onNodeWithText("Resume").performClick()
            onNodeWithTag("places").performClick()
            assertEquals(listOf("setup", "resume", "places"), calls)
        }
}
