package app.lares.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.Person
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.DatedItem
import app.lares.client.ui.family.PersonRoute
import app.lares.client.ui.family.PersonViewModel
import app.lares.client.ui.main.MainUiState
import app.lares.client.ui.main.TodayActions
import app.lares.client.ui.main.TodayScreen
import app.lares.client.ui.main.TodayUiState
import app.lares.client.ui.main.TodayUpcomingUi
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Today «Скоро» (CAL-019, mockup `Main.dc.html`) and the person page dates (CAL-004, CAL-005), plan M3-08. */
@OptIn(ExperimentalTestApi::class)
class TodayUpcomingUiTest {
    private val today = LocalDate(2026, 10, 23) // Friday

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val olga = Person("p1", PersonData("Ольга", color = PaletteColor.PINK))
    private val dima = Person("p2", PersonData("Дмитрий", color = PaletteColor.GREEN))
    private val grandma = Person("p4", PersonData("Бабушка Валя", birthYear = 1948, birthMonth = 10, birthDay = 26))

    private fun meeting(date: LocalDate) =
        CalendarItem.Event(
            EventOccurrence(
                "e1",
                date,
                date,
                date,
                LocalTime(18, 30),
                LocalTime(20, 0),
                "Родительское собрание",
                "Школа №12",
                null,
                setOf("p1", "p2"),
                false,
            ),
            EventKind.EVENT,
            listOf(olga, dima),
            emptyList(),
            repeating = false,
            years = null,
            continuesBefore = false,
            continuesAfter = false,
        )

    @Test
    fun `rows like the mockup, taps and more`() =
        runComposeUiTest {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val monday = LocalDate(2026, 10, 26)
            val wednesday = LocalDate(2026, 10, 28)
            val upcoming =
                TodayUpcomingUi(
                    listOf(DatedItem(monday, CalendarItem.Birthday(grandma, monday, 78)), DatedItem(wednesday, meeting(wednesday))),
                    more = 2,
                    today = today,
                    allNames = listOf("Ольга", "Дмитрий"),
                )
            val opened = mutableListOf<String>()
            setContent {
                LaresTheme(darkTheme = false) {
                    TodayScreen(
                        MainUiState(name = "Ольга"),
                        {},
                        {},
                        TodayUiState(loaded = true, upcoming = upcoming),
                        TodayActions(
                            onEvent = { id, date -> opened += "event:$id:$date" },
                            onPerson = { opened += "person:$it" },
                            onCalendar = { opened += "calendar" },
                            onCalendarList = { opened += "list" },
                        ),
                    )
                }
            }
            onNodeWithText("Скоро").assertIsDisplayed()
            onNodeWithText("Бабушка Валя, 78 лет", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Понедельник · через 3 дня", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Родительское собрание", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Среда, 18:30 · Школа №12", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Ещё 2", useUnmergedTree = true).assertIsDisplayed()

            onNodeWithTag("today_upcoming_0").performClick()
            onNodeWithTag("today_upcoming_1").performClick()
            onNodeWithTag("today_upcoming_more").performScrollTo().performClick()
            onNodeWithTag("today_calendar").performClick()
            assertEquals(listOf("person:p4", "event:e1:$wednesday", "list", "calendar"), opened)
        }

    @Test
    fun `person page shows the countdown and memorable dates with add`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                val vm =
                    PersonViewModel(
                        core.session,
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        Clock.System,
                        "p1",
                        core.koin.get(),
                    )
                awaitState(vm.state) { it.loaded && it.dates?.memorable?.isNotEmpty() == true }
                val opened = mutableListOf<String>()
                setContent {
                    LaresTheme(darkTheme = false) {
                        PersonRoute(
                            "p1",
                            onBack = {},
                            onEvent = { opened += "event:$it" },
                            onAddMemorable = { opened += "add:$it" },
                            viewModel = vm,
                        )
                    }
                }
                val years = CalendarUiFixture.today.year - 2010
                onNodeWithText("Wedding anniversary · $years years", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                onNodeWithTag("person_memorable_${CalendarUiFixture.E_ANNIV}").performClick()
                onNodeWithTag("person_add_memorable").performScrollTo().performClick()
                assertEquals(listOf("event:${CalendarUiFixture.E_ANNIV}", "add:p1"), opened)
            }
        }
}
