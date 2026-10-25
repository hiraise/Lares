package app.lares.client.ui

import app.lares.client.data.calendar.ANYA
import app.lares.client.data.calendar.CAL_KID
import app.lares.client.data.calendar.CalendarFixture
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.P_ANYA
import app.lares.client.data.calendar.timedEvent
import app.lares.client.ui.CalendarUiFixture.E_ANNIV
import app.lares.client.ui.CalendarUiFixture.E_DANCE
import app.lares.client.ui.CalendarUiFixture.E_DENTIST
import app.lares.client.ui.CalendarUiFixture.E_HOLIDAY
import app.lares.client.ui.calendar.EventArgs
import app.lares.client.ui.calendar.EventEvent
import app.lares.client.ui.calendar.EventViewModel
import app.lares.client.ui.calendar.RepeatScope
import app.lares.client.ui.common.load
import app.lares.contract.entities.EventKind
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event page (plan M3-05, CAL-014, CAL-006, CAL-015). */
class EventViewModelTest {
    private val today = CalendarUiFixture.today
    private val week = CalendarUiFixture.week

    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun UiTestCore.event(
        id: String,
        occurrence: LocalDate? = null,
    ) = koin.get<EventViewModel> { parametersOf(EventArgs(id, occurrence)) }

    private fun loaded(vm: EventViewModel) = awaitState(vm.state) { it.detail != null }

    @Test
    fun `facts of timed, all-day, repeating and memorable events`() {
        CalendarUiFixture.core().use { core ->
            val dentist = loaded(core.event(E_DENTIST))
            assertEquals(LocalTime(16, 0), dentist.detail!!.occ!!.startTime)
            assertEquals("Clinic", dentist.detail.occ!!.place)
            assertEquals(listOf("Carol"), dentist.detail.participants.map { it.data.name })
            assertEquals("Olga", dentist.detail.creator?.name)
            assertFalse(dentist.asksScope)
            assertTrue(dentist.detail.canEdit)
            // Olga is not a participant: the event's reminder is not hers.
            assertNull(dentist.detail.eventReminder)

            val holiday = loaded(core.event(E_HOLIDAY))
            assertTrue(holiday.detail!!.occ!!.allDay)
            assertEquals(today.plus(6, DateTimeUnit.DAY), holiday.detail.occ!!.endDate)
            assertEquals(listOf("t1"), holiday.detail.tags.map { it.id })

            val dance = loaded(core.event(E_DANCE, week))
            assertTrue(dance.asksScope)
            assertTrue(dance.detail!!.occ!!.changed, "the occurrence in a week is moved")
            assertEquals(LocalTime(18, 0), dance.detail.occ!!.startTime)
            assertEquals(today.plus(56, DateTimeUnit.DAY), dance.detail.event.repeatUntil)

            val anniversary = loaded(core.event(E_ANNIV))
            assertTrue(anniversary.memorable)
            assertFalse(anniversary.asksScope, "memorable dates change as a whole")
            assertEquals(EventKind.MEMORABLE_DATE, anniversary.detail!!.event.kind)
            assertEquals(today.plus(1, DateTimeUnit.DAY).year - 2010, anniversary.detail.years)
        }
    }

    @Test
    fun `my reminder writes my row and «as in the event» clears it`() {
        // Reminder rows derive their ids from UUIDs, so this runs on the calendar core fixture.
        Dispatchers.setMain(Dispatchers.Default)
        val fixture = CalendarFixture()
        val id = "01928c4e-0000-7000-8000-00000000e101"
        runBlocking {
            fixture.family()
            fixture.loginAs(ANYA, *CAL_KID)
            val date = LocalDate(2026, 10, 27)
            fixture.putEvent(
                id,
                timedEvent("Стоматолог", date, LocalTime(16, 0), LocalTime(17, 0), setOf(P_ANYA)).copy(reminderMinutesBefore = 60),
            )
        }
        val vm = EventViewModel(fixture.repository, fixture.calendarWriter, EventArgs(id))
        val start = loaded(vm).detail!!
        assertEquals(60, start.eventReminder, "Аня is a participant")
        assertEquals(60, start.myReminder)
        assertFalse(start.ownReminderSet)

        vm.setMyReminder(120)
        val mine = awaitState(vm.state) { it.detail?.myReminder == 120 }.detail!!
        assertTrue(mine.ownReminderSet)
        assertTrue(runBlocking { fixture.outbox() }.any { it.type == EntityTypes.EVENT_REMINDER })

        vm.setMyReminder(null)
        assertTrue(awaitState(vm.state) { it.detail?.ownReminderSet == true && it.detail.myReminder == null }.loaded)

        vm.clearMyReminder()
        val back = awaitState(vm.state) { it.detail?.myReminder == 60 }.detail!!
        assertFalse(back.ownReminderSet)
    }

    @Test
    fun `deleting only this occurrence cancels it and closes with a notice`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.event(E_DANCE, week)
            loaded(vm)
            vm.delete(RepeatScope.THIS)
            val close = assertIs<EventEvent.Close>(TaskUiFixture.next(vm.events))
            assertEquals("Occurrence deleted", runBlocking { close.message!!.load() })
            val days = TaskUiFixture.next(core.koin.get<CalendarRepository>().observeDays(week, week))
            assertTrue(days.single().items.none { it is CalendarItem.Event }, "the dance of that week is gone")
            val next =
                TaskUiFixture.next(
                    core.koin.get<CalendarRepository>().observeDays(week.plus(7, DateTimeUnit.DAY), week.plus(7, DateTimeUnit.DAY)),
                )
            assertTrue(next.single().items.isNotEmpty(), "the next one stays")
        }
    }

    @Test
    fun `deleting this and following ends the series the day before`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.event(E_DANCE, week)
            loaded(vm)
            vm.delete(RepeatScope.FOLLOWING)
            val close = assertIs<EventEvent.Close>(TaskUiFixture.next(vm.events))
            assertNull(close.message)
            val detail =
                awaitState(core.koin.get<CalendarRepository>().observeDetail(E_DANCE)) {
                    it?.event?.repeatUntil != today.plus(56, DateTimeUnit.DAY)
                }
            assertEquals(week.minus(1, DateTimeUnit.DAY), detail!!.event.repeatUntil)
        }
    }

    @Test
    fun `deleting all removes the event and the page closes`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.event(E_DANCE, week)
            loaded(vm)
            vm.delete(RepeatScope.ALL)
            assertIs<EventEvent.Close>(TaskUiFixture.next(vm.events))
            assertTrue(awaitState(vm.state) { it.gone }.gone)
        }
    }

    @Test
    fun `a kid may not edit others' events`() {
        CalendarUiFixture.core(FamilyFixture.carol).use { core ->
            assertFalse(loaded(core.event(E_DANCE, week)).detail!!.canEdit)
        }
    }
}
