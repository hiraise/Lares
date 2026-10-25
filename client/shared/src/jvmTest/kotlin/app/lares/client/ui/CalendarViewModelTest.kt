package app.lares.client.ui

import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarViews
import app.lares.client.platform.AppPreferences
import app.lares.client.platform.PreferencesStore
import app.lares.client.ui.calendar.CalendarMode
import app.lares.client.ui.calendar.CalendarRow
import app.lares.client.ui.calendar.CalendarUiState
import app.lares.client.ui.calendar.CalendarViewModel
import app.lares.client.ui.tasks.TaskEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Calendar tab (plan M3-05, CAL-008, CAL-009, TAG-003). */
class CalendarViewModelTest {
    private val today = CalendarUiFixture.today

    private fun day(n: Int) = today.plus(n, DateTimeUnit.DAY)

    private fun CalendarRow.id(): String =
        when (val item = item) {
            is CalendarItem.Event -> item.occ.eventId
            is CalendarItem.Birthday -> "birthday-${item.person.id}"
            is CalendarItem.Task -> item.view.id
        }

    private fun CalendarUiState.dayIds() = day.map { it.id() }

    private fun loaded(vm: CalendarViewModel) = awaitState(vm.state) { it.loaded && it.day.isNotEmpty() }

    @Test
    fun `the view is remembered in the preferences`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            assertEquals(CalendarMode.MONTH, loaded(vm).mode)
            vm.setMode(CalendarMode.LIST)
            awaitState(vm.state) { it.mode == CalendarMode.LIST && it.listDays.isNotEmpty() }
            val stored =
                runBlocking {
                    core.koin
                        .get<PreferencesStore>()
                        .preferences
                        .first()
                }
            assertEquals(AppPreferences.CALENDAR_VIEW_LIST, stored.calendarView)
            val again = core.koin.get<CalendarViewModel>()
            assertEquals(CalendarMode.LIST, awaitState(again.state) { it.loaded }.mode)
        }
    }

    @Test
    fun `today's day list has the birthday, events and tasks`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            val state = loaded(vm)
            assertEquals(today, state.nav.selected)
            assertTrue(state.atToday)
            assertTrue(state.canCreate)
            // Birthday first, then the timed dance, then the overdue task, then today's untimed tasks.
            assertEquals("birthday-p4", state.dayIds().first())
            assertTrue(
                state.dayIds().containsAll(listOf(CalendarUiFixture.E_DANCE, "t-over", "t-today", "t-routine")),
                state.dayIds().toString(),
            )
            val task = state.day.single { it.id() == "t-over" }
            assertTrue((task.item as CalendarItem.Task).overdue)
            assertTrue(task.task!!.canToggle)
            // The grid of this month has dots: Grandma (orange) today, the anniversary of Olga and Boris tomorrow.
            val cells = state.grids.getValue(LocalDate(today.year, today.month, 1))
            assertTrue(cells.single { it.date == today }.today)
            assertTrue(cells.single { it.date == today }.dots.isNotEmpty())
        }
    }

    @Test
    fun `selecting a day and paging months`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            loaded(vm)
            vm.select(day(2))
            assertEquals(listOf(CalendarUiFixture.E_DENTIST), awaitState(vm.state) { it.nav.selected == day(2) }.dayIds())

            val jan31 = LocalDate(today.year, 1, 31)
            vm.select(jan31)
            assertEquals(LocalDate(today.year, 1, 1), awaitState(vm.state) { it.nav.selected == jan31 }.nav.month)
            assertFalse(vm.state.value.atToday)
            vm.next()
            val february = awaitState(vm.state) { it.nav.month.month.ordinal == 1 }
            assertEquals(LocalDate(today.year, 2, 1), february.nav.selected, "no 31st in February: the 1st")
            vm.select(LocalDate(today.year, 2, 15))
            vm.showMonth(LocalDate(today.year, 3, 1))
            assertEquals(LocalDate(today.year, 3, 15), awaitState(vm.state) { it.nav.month.month.ordinal == 2 }.nav.selected)
            vm.previous()
            assertEquals(LocalDate(today.year, 2, 15), awaitState(vm.state) { it.nav.month.month.ordinal == 1 }.nav.selected)
            vm.goToToday()
            assertTrue(awaitState(vm.state) { it.nav.selected == today }.atToday)
        }
    }

    @Test
    fun `filters by person, tag and tasks`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            loaded(vm)
            vm.setPerson("p3")
            val carol = awaitState(vm.state) { it.filter.personId == "p3" && "birthday-p4" !in it.dayIds() }
            assertEquals(
                setOf(CalendarUiFixture.E_DANCE, "t-routine"),
                carol.dayIds().toSet(),
                "Carol's dance and chore; not Grandma or others' tasks",
            )
            assertEquals("Carol", carol.filterPerson?.data?.name)
            assertTrue(carol.filtered)
            vm.select(day(3))
            assertEquals(
                listOf(CalendarUiFixture.E_HOLIDAY),
                awaitState(vm.state) { it.nav.selected == day(3) }.dayIds(),
                "whole-family events stay",
            )

            vm.setPerson(null)
            vm.setTag("t1")
            vm.select(today)
            val tagged = awaitState(vm.state) { it.filter.personId == null && it.filter.tagId == "t1" && it.nav.selected == today }
            assertEquals(listOf("t-today"), tagged.dayIds(), "only tagged items, no birthdays")
            assertEquals(listOf("t1"), tagged.tags.map { it.id })

            vm.setTag(null)
            vm.setShowTasks(false)
            val noTasks = awaitState(vm.state) { !it.filter.showTasks && it.filter.tagId == null }
            assertTrue(noTasks.hidesTasks)
            assertEquals(listOf("birthday-p4", CalendarUiFixture.E_DANCE), noTasks.dayIds())

            vm.resetFilter()
            assertFalse(awaitState(vm.state) { it.filter.showTasks }.filtered)
        }
    }

    @Test
    fun `the list grows forward and back and ticks tasks`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            vm.setMode(CalendarMode.LIST)
            val state = awaitState(vm.state) { it.mode == CalendarMode.LIST && it.listDays.isNotEmpty() }
            assertEquals(today, state.listDays.first().date)
            assertEquals(day(CalendarViewModel.LIST_STEP_DAYS), state.nav.listTo)
            assertTrue(state.listDays.all { it.rows.isNotEmpty() }, "empty days are left out")

            vm.extendList()
            assertEquals(day(2 * CalendarViewModel.LIST_STEP_DAYS), awaitState(vm.state) { it.nav.listTo > state.nav.listTo }.nav.listTo)
            vm.showPast()
            val past = awaitState(vm.state) { it.nav.listFrom < today }
            assertEquals(today.minus(CalendarViewModel.PAST_STEP_DAYS, DateTimeUnit.DAY), past.nav.listFrom)
            assertTrue(
                past.listDays.any { day ->
                    day.date == today.minus(14, DateTimeUnit.DAY) &&
                        day.rows.any { it.id() == CalendarUiFixture.E_DANCE }
                },
            )

            val row =
                past.listDays
                    .single { it.date == today }
                    .rows
                    .single { it.id() == "t-today" }
            vm.toggle(row.task!!)
            assertIs<TaskEvent.Completed>(TaskUiFixture.next(vm.events))
            val done =
                awaitState(vm.state) { s ->
                    s.listDays
                        .single { it.date == today }
                        .rows
                        .any { it.id() == "t-today" && it.task!!.done }
                }
            assertTrue(done.listDays.isNotEmpty())
        }
    }

    @Test
    fun `members without calendar create get no add button`() {
        CalendarUiFixture.core(FamilyFixture.carol, CalendarUiFixture.kidsCannotCreate).use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            assertFalse(loaded(vm).canCreate)
        }
        CalendarUiFixture.core(FamilyFixture.carol).use { core ->
            assertTrue(loaded(core.koin.get<CalendarViewModel>()).canCreate, "Kids create events by default")
        }
    }

    @Test
    fun `the week pages by seven days and returns to today`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            loaded(vm)
            val monday = CalendarViews.mondayOnOrBefore(today)
            vm.setMode(CalendarMode.WEEK)
            val week = awaitState(vm.state) { it.mode == CalendarMode.WEEK && it.weeks.isNotEmpty() }
            assertEquals(monday, week.nav.weekStart)
            assertTrue(week.atToday)
            assertEquals(setOf(monday.minus(7, DateTimeUnit.DAY), monday, monday.plus(7, DateTimeUnit.DAY)), week.weeks.keys)
            val dance =
                week.weeks
                    .getValue(monday)
                    .single { it.date == today }
                    .blocks
                    .single()
            assertEquals(CalendarUiFixture.E_DANCE, dance.item.occ.eventId)
            assertEquals(17 * 60 + 30 to 18 * 60 + 45, dance.startMinute to dance.endMinute)
            assertTrue(
                week.weeks.values
                    .flatten()
                    .all { day -> day.allDay.none { it is CalendarItem.Task } },
                "no tasks in the week",
            )

            vm.next()
            val next = awaitState(vm.state) { it.nav.weekStart == monday.plus(7, DateTimeUnit.DAY) }
            assertFalse(next.atToday)
            val moved =
                next.weeks
                    .getValue(next.nav.weekStart)
                    .single { it.date == CalendarUiFixture.week }
                    .blocks
                    .single()
            assertEquals(18 * 60, moved.startMinute, "the changed occurrence")

            vm.previous()
            vm.previous()
            assertEquals(monday.minus(7, DateTimeUnit.DAY), awaitState(vm.state) { it.nav.weekStart < monday }.nav.weekStart)
            vm.showWeek(monday.plus(21, DateTimeUnit.DAY))
            awaitState(vm.state) { it.nav.weekStart == monday.plus(21, DateTimeUnit.DAY) }
            vm.goToToday()
            assertTrue(awaitState(vm.state) { it.nav.weekStart == monday }.atToday)
        }
    }

    @Test
    fun `switching from a month day to the week shows that day's week`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.koin.get<CalendarViewModel>()
            loaded(vm)
            val later = today.plus(30, DateTimeUnit.DAY)
            vm.select(later)
            awaitState(vm.state) { it.nav.selected == later }
            vm.setMode(CalendarMode.WEEK)
            val week = awaitState(vm.state) { it.mode == CalendarMode.WEEK && it.weeks.isNotEmpty() }
            assertEquals(CalendarViews.mondayOnOrBefore(later), week.nav.weekStart)
        }
    }
}
