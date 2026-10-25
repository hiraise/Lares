package app.lares.client.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.CalendarUiFixture.E_DENTIST
import app.lares.client.ui.calendar.CalendarActions
import app.lares.client.ui.calendar.CalendarMode
import app.lares.client.ui.calendar.CalendarRowActions
import app.lares.client.ui.calendar.CalendarScreen
import app.lares.client.ui.calendar.CalendarViewModel
import app.lares.client.ui.theme.LaresTheme
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Calendar tab on a real client core (plan M3-05, CAL-004, CAL-008, CAL-009, TAG-003). */
@OptIn(ExperimentalTestApi::class)
class CalendarUiTest {
    private val today = CalendarUiFixture.today

    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun day(n: Int) = today.plus(n, DateTimeUnit.DAY)

    /** The tab wired to its view model; [opened] collects person and event taps. */
    private fun ComposeUiTest.show(
        vm: CalendarViewModel,
        opened: MutableList<String> = mutableListOf(),
    ) {
        awaitState(vm.state) { it.loaded && it.day.isNotEmpty() }
        setContent {
            val state by vm.state.collectAsState()
            LaresTheme(darkTheme = false) {
                CalendarScreen(
                    state,
                    CalendarActions(
                        onMode = vm::setMode,
                        onMonth = vm::showMonth,
                        onSelect = vm::select,
                        onPrevious = vm::previous,
                        onNext = vm::next,
                        onToday = vm::goToToday,
                        onExtendList = vm::extendList,
                        onShowPast = vm::showPast,
                        onPerson = vm::setPerson,
                        onTag = vm::setTag,
                        onShowTasks = vm::setShowTasks,
                        onResetFilter = vm::resetFilter,
                        rows =
                            CalendarRowActions(
                                onEvent = { id, _ -> opened += "event:$id" },
                                onPerson = { opened += "person:$it" },
                                onTask = { opened += "task:$it" },
                                onToggle = vm::toggle,
                            ),
                    ),
                )
            }
        }
    }

    private fun ComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.waitGone(tag: String) =
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

    @Test
    fun `month grid shows today and dots, a tap shows the day's rows`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                show(core.koin.get())
                onNodeWithTag("today_marker", useUnmergedTree = true).assertExists()
                assertTrue(onAllNodesWithTag("dot_$today", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "Grandma's birthday")
                assertEquals(
                    2,
                    onAllNodesWithTag("dot_${day(1)}", useUnmergedTree = true).fetchSemanticsNodes().size,
                    "Olga and Boris",
                )
                onNodeWithTag("day_header", useUnmergedTree = true).assertTextEquals("Today, ${CalendarUiTexts.dayMonth(today)}")
                onNodeWithText("Grandma, ${today.year - 1948} years", useUnmergedTree = true).assertExists()

                onNodeWithTag("day_${day(2)}").performClick()
                waitFor("event_row_$E_DENTIST")
                onNodeWithText("Dentist", useUnmergedTree = true).assertExists()
                onNodeWithText("Carol · Clinic", useUnmergedTree = true).assertExists()
                onNodeWithText("16:00", useUnmergedTree = true).assertExists()
                onNodeWithTag("cal_today").assertExists()
            }
        }

    @Test
    fun `a birthday row opens the person`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                val opened = mutableListOf<String>()
                show(core.koin.get(), opened)
                onNodeWithTag("birthday_row_p4").performScrollTo().performClick()
                assertEquals(listOf("person:p4"), opened)
            }
        }

    @Test
    fun `list headers say today and tomorrow`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                val vm = core.koin.get<CalendarViewModel>()
                show(vm)
                onNodeWithText("List").performClick()
                awaitState(vm.state) { it.mode == CalendarMode.LIST && it.listDays.isNotEmpty() }
                waitFor("list_header_$today")
                onNodeWithTag("list_header_$today", useUnmergedTree = true).assertTextEquals("Today")
                onNodeWithTag("list_header_${day(1)}", useUnmergedTree = true).assertTextEquals("Tomorrow")
                onNodeWithTag("cal_previous").assertDoesNotExist()
            }
        }

    @Test
    fun `a filter chip removes its filter`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                val vm = core.koin.get<CalendarViewModel>()
                show(vm)
                vm.setPerson("p3")
                waitFor("cal_chip_person")
                onNodeWithTag("cal_filter_badge", useUnmergedTree = true).assertExists()
                waitGone("birthday_row_p4")
                onNodeWithTag("cal_chip_person").performClick()
                waitGone("cal_chip_person")
                waitFor("birthday_row_p4")
            }
        }

    @Test
    fun `turning off show tasks hides them`() =
        runComposeUiTest {
            CalendarUiFixture.core().use { core ->
                val vm = core.koin.get<CalendarViewModel>()
                show(vm)
                waitFor("cal_task_row_t-today")
                onNodeWithTag("cal_filter").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("filter_show_tasks")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("filter_show_tasks").performClick()
                waitGone("cal_task_row_t-today")
                awaitState(vm.state) { it.hidesTasks }
            }
        }
}

/** English date texts the tests compare with. */
private object CalendarUiTexts {
    private val months =
        listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun dayMonth(date: kotlinx.datetime.LocalDate) = "${months[date.month.ordinal]} ${date.day}"
}
