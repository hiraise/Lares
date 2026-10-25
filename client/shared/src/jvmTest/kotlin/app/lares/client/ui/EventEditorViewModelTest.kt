package app.lares.client.ui

import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.EventDetail
import app.lares.client.ui.CalendarUiFixture.E_ANNIV
import app.lares.client.ui.CalendarUiFixture.E_DANCE
import app.lares.client.ui.calendar.EventEditorArgs
import app.lares.client.ui.calendar.EventEditorResult
import app.lares.client.ui.calendar.EventEditorUiState
import app.lares.client.ui.calendar.EventEditorViewModel
import app.lares.client.ui.calendar.RepeatScope
import app.lares.client.ui.common.RepeatPreset
import app.lares.contract.entities.EventKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event editor (plan M3-06): create, «Все», «Только это», «Это и следующие». */
class EventEditorViewModelTest {
    private val today = CalendarUiFixture.today
    private val week = CalendarUiFixture.week
    private val danceStart = today.minus(14, DateTimeUnit.DAY)

    private fun UiTestCore.editor(args: EventEditorArgs = EventEditorArgs()) = koin.get<EventEditorViewModel> { parametersOf(args) }

    private fun loaded(vm: EventEditorViewModel) = awaitState(vm.state) { it.loaded && it.allowed }

    private fun saved(vm: EventEditorViewModel): EventEditorResult {
        vm.save()
        return awaitState(vm.state) { it.result != null }.result!!
    }

    private fun UiTestCore.detail(
        id: String,
        occurrence: LocalDate? = null,
        until: (EventDetail) -> Boolean = { true },
    ): EventDetail = awaitState(koin.get<CalendarRepository>().observeDetail(id, occurrence)) { it != null && until(it) }!!

    @Test
    fun `creates a timed event with a participant, place and reminder`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(date = week, hour = 16))
            val start = loaded(vm)
            assertTrue(start.isNew)
            assertEquals(LocalTime(16, 0), start.form.startTime)
            assertEquals(LocalTime(17, 0), start.form.endTime)
            assertFalse(start.changed)
            vm.onTitle("Dentist")
            vm.toggleParticipant("p3")
            vm.onPlace("Clinic on Lenina")
            vm.onReminder(60)
            assertTrue(awaitState(vm.state) { it.form.reminderMinutesBefore == 60 }.changed)
            val created = assertIs<EventEditorResult.Created>(saved(vm))
            val event = core.detail(created.id).event
            assertEquals("Dentist", event.title)
            assertEquals(week, event.startDate)
            assertEquals(setOf("p3"), event.participantIds)
            assertEquals("Clinic on Lenina", event.place)
            assertEquals(60, event.reminderMinutesBefore)
            assertEquals("m1", event.createdBy)
        }
    }

    @Test
    fun `creates an all-day event over several days`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(date = week))
            loaded(vm)
            vm.onTitle("Holiday")
            vm.onAllDay(true)
            vm.onEndDate(week.plus(10, DateTimeUnit.DAY))
            val created = assertIs<EventEditorResult.Created>(saved(vm))
            val event = core.detail(created.id).event
            assertTrue(event.allDay)
            assertNull(event.startTime)
            assertEquals(week.plus(10, DateTimeUnit.DAY), event.endDate)
        }
    }

    @Test
    fun `refuses a span over a year`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(date = week))
            loaded(vm)
            vm.onEndDate(week.plus(400, DateTimeUnit.DAY))
            val state = awaitState(vm.state) { "endDate" in it.fieldErrors }
            assertEquals(week, state.form.endDate, "the end stays")
        }
    }

    @Test
    fun `creates a repeating event with an end date`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(date = today, hour = 17))
            loaded(vm)
            vm.onTitle("Dance")
            vm.onPreset(RepeatPreset.WEEKLY)
            vm.onRepeatUntil(today.plus(60, DateTimeUnit.DAY))
            val created = assertIs<EventEditorResult.Created>(saved(vm))
            val event = core.detail(created.id).event
            assertEquals(setOf(today.dayOfWeek), event.recurrence!!.weekdays)
            assertEquals(today.plus(60, DateTimeUnit.DAY), event.repeatUntil)
        }
    }

    @Test
    fun `creates a memorable date with years`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(memorable = true, personId = "p1"))
            val start = loaded(vm)
            assertEquals(EventKind.MEMORABLE_DATE, start.form.kind)
            assertEquals(setOf("p1"), start.form.participantIds)
            vm.onTitle("Wedding anniversary")
            vm.toggleParticipant("p2")
            vm.onMemorableDate(12, 6, 2010)
            vm.onCountYears(true)
            awaitState(vm.state) { it.form.countYears }
            val created = assertIs<EventEditorResult.Created>(saved(vm))
            val detail = core.detail(created.id)
            assertEquals(EventKind.MEMORABLE_DATE, detail.event.kind)
            assertEquals(LocalDate(2010, 6, 12), detail.event.startDate)
            assertTrue(detail.event.countYears)
            assertEquals(setOf("p1", "p2"), detail.event.participantIds)
            assertNotNull(detail.years)
        }
    }

    @Test
    fun `prefills the date, hour, person and tag`() {
        CalendarUiFixture.core().use { core ->
            val state = loaded(core.editor(EventEditorArgs(date = week, hour = 23, personId = "p3", tagId = "t1")))
            assertEquals(week, state.form.startDate)
            assertEquals(LocalTime(23, 0), state.form.startTime)
            assertEquals(week.plus(1, DateTimeUnit.DAY), state.form.endDate, "the hour ends after midnight")
            assertEquals(setOf("p3"), state.form.participantIds)
            assertEquals(setOf("t1"), state.form.tagIds)
            assertEquals(listOf("Boris", "Carol", "Olga", "Grandma"), state.participants.map { it.name }, "members by name, then relatives")

            val unknown = loaded(core.editor(EventEditorArgs(personId = "nobody", tagId = "nothing")))
            assertTrue(unknown.form.participantIds.isEmpty())
            assertTrue(unknown.form.tagIds.isEmpty())
            assertEquals(today, unknown.form.startDate)
        }
    }

    @Test
    fun `editing all with a schedule change over changed occurrences asks first and resets them`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = week, scope = RepeatScope.ALL))
            val start = loaded(vm)
            assertEquals(1, start.changedCount)
            assertEquals(danceStart, start.form.startDate, "«Все» starts from the series")
            vm.onTitle("Dance class")
            vm.onStartDate(danceStart.plus(1, DateTimeUnit.DAY))
            vm.save()
            assertTrue(awaitState(vm.state) { it.confirmReset }.confirmReset)
            vm.dismissReset()
            assertFalse(awaitState(vm.state) { !it.confirmReset }.confirmReset)
            vm.save(confirmed = true)
            assertIs<EventEditorResult.Saved>(awaitState(vm.state) { it.result != null }.result)
            val detail = core.detail(E_DANCE) { it.event.title == "Dance class" }
            assertEquals(0, detail.changedCount)
            assertEquals(setOf(danceStart.plus(1, DateTimeUnit.DAY).dayOfWeek), detail.rule!!.weekdays)
        }
    }

    @Test
    fun `editing all without a schedule change saves at once`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = week, scope = RepeatScope.ALL))
            loaded(vm)
            vm.onPlace("Hall")
            assertIs<EventEditorResult.Saved>(saved(vm))
            assertEquals(1, core.detail(E_DANCE) { it.event.place == "Hall" }.changedCount, "the moved occurrence stays")
        }
    }

    @Test
    fun `only this writes the occurrence row and keeps its earlier change`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = week, scope = RepeatScope.THIS))
            val start = loaded(vm)
            assertTrue(start.onlyThis)
            assertEquals(LocalTime(18, 0), start.form.startTime, "the form starts from the moved occurrence")
            vm.onPlace("Small hall")
            assertIs<EventEditorResult.Saved>(saved(vm))
            val occ = core.detail(E_DANCE, week) { it.occ?.place == "Small hall" }.occ!!
            assertEquals(LocalTime(18, 0), occ.startTime)
            assertNull(core.detail(E_DANCE).event.place, "the series is unchanged")

            val other = core.editor(EventEditorArgs(E_DANCE, occurrence = week.plus(7, DateTimeUnit.DAY), scope = RepeatScope.THIS))
            loaded(other)
            other.onStartDate(week.plus(8, DateTimeUnit.DAY))
            assertIs<EventEditorResult.Saved>(saved(other))
            val moved = core.detail(E_DANCE, week.plus(7, DateTimeUnit.DAY)) { it.occ?.changed == true }.occ!!
            assertEquals(week.plus(8, DateTimeUnit.DAY), moved.startDate)
        }
    }

    @Test
    fun `this and following on a later date splits the series`() {
        CalendarUiFixture.core().use { core ->
            val from = week.plus(7, DateTimeUnit.DAY)
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = from, scope = RepeatScope.FOLLOWING))
            val start = loaded(vm)
            assertEquals(from, start.form.startDate)
            assertNotNull(start.form.recurrence)
            vm.onStartTime(LocalTime(18, 0))
            val saved = assertIs<EventEditorResult.Saved>(saved(vm))
            val newId = assertNotNull(saved.splitId)
            assertEquals(
                from.minus(1, DateTimeUnit.DAY),
                core
                    .detail(E_DANCE) {
                        it.event.repeatUntil != today.plus(56, DateTimeUnit.DAY)
                    }.event.repeatUntil,
            )
            val split = core.detail(newId).event
            assertEquals(from, split.startDate)
            assertEquals(LocalTime(18, 0), split.startTime)
            assertEquals(LocalTime(19, 15), split.endTime)
            assertEquals(E_DANCE, split.splitFrom)
            assertEquals(today.plus(56, DateTimeUnit.DAY), split.repeatUntil)
        }
    }

    @Test
    fun `this and following on the first date changes the series`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = danceStart, scope = RepeatScope.FOLLOWING))
            loaded(vm)
            vm.onTitle("Dance class")
            val saved = assertIs<EventEditorResult.Saved>(saved(vm))
            assertNull(saved.splitId)
            assertEquals(danceStart, core.detail(E_DANCE) { it.event.title == "Dance class" }.event.startDate)
        }
    }

    @Test
    fun `a memorable date is edited as a whole`() {
        CalendarUiFixture.core().use { core ->
            val state =
                loaded(core.editor(EventEditorArgs(E_ANNIV, occurrence = today.plus(1, DateTimeUnit.DAY), scope = RepeatScope.THIS)))
            assertEquals(RepeatScope.ALL, state.scope)
            assertTrue(state.form.memorable)
            assertTrue(state.form.yearKnown)
            assertEquals(2010, state.form.startDate.year)
        }
    }

    @Test
    fun `changes are noticed for the discard question`() {
        CalendarUiFixture.core().use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE))
            val start: EventEditorUiState = loaded(vm)
            assertFalse(start.changed)
            assertFalse(start.isNew)
            vm.onNotes("Bring shoes")
            assertTrue(awaitState(vm.state) { it.form.notes.isNotEmpty() }.changed)
        }
    }

    @Test
    fun `a kid may not edit others' events`() {
        CalendarUiFixture.core(FamilyFixture.carol).use { core ->
            val vm = core.editor(EventEditorArgs(E_DANCE, occurrence = week, scope = RepeatScope.THIS))
            assertEquals(EventEditorResult.Forbidden, awaitState(vm.state) { it.result != null }.result)
        }
    }
}
