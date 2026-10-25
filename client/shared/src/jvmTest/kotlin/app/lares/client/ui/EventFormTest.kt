package app.lares.client.ui

import app.lares.client.resources.Res
import app.lares.client.resources.cal_error_before_start
import app.lares.client.ui.calendar.EventForm
import app.lares.client.ui.common.RepeatPreset
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.validation.Validators
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of the event editor (plan M3-06). */
class EventFormTest {
    private val tue = LocalDate(2026, 10, 27)
    private val today = LocalDate(2026, 10, 25)

    private fun timed(
        start: LocalTime = LocalTime(16, 0),
        end: LocalTime = LocalTime(17, 0),
    ) = EventForm
        .new(tue, start.hour)
        .withEndTime(end)
        .withStartTime(start)
        .withTitle("Dentist")

    @Test
    fun `a new event lasts one hour and an end past midnight moves to the next day`() {
        val form = EventForm.new(tue, 16)
        assertEquals(LocalTime(16, 0), form.startTime)
        assertEquals(LocalTime(17, 0), form.endTime)
        assertEquals(tue, form.endDate)
        val late = EventForm.new(tue, 23)
        assertEquals(LocalTime(0, 0), late.endTime)
        assertEquals(LocalDate(2026, 10, 28), late.endDate)
        assertEquals(23, EventForm.nextHour(LocalTime(23, 40)))
        assertEquals(15, EventForm.nextHour(LocalTime(14, 0)))
        assertEquals(EventKind.MEMORABLE_DATE, EventForm.new(tue, 9, memorable = true).kind)
    }

    @Test
    fun `changing the start keeps the duration, also over midnight`() {
        val form = timed(LocalTime(16, 0), LocalTime(17, 30))
        val later = form.withStartTime(LocalTime(23, 0))
        assertEquals(LocalTime(0, 30), later.endTime)
        assertEquals(LocalDate(2026, 10, 28), later.endDate)
        val moved = later.withStartDate(LocalDate(2026, 11, 2))
        assertEquals(LocalDate(2026, 11, 2), moved.startDate)
        assertEquals(LocalDate(2026, 11, 3), moved.endDate)
        assertEquals(LocalTime(0, 30), moved.endTime)

        val holiday = EventForm.new(tue, 9).withAllDay(true).withEndDate(LocalDate(2026, 11, 6))!!
        val shifted = holiday.withStartDate(LocalDate(2026, 11, 10))
        assertEquals(LocalDate(2026, 11, 20), shifted.endDate, "all-day keeps its length in days")
    }

    @Test
    fun `an end before the start becomes start plus one hour or the start date`() {
        val form = timed()
        val early = form.withEndTime(LocalTime(15, 0))
        assertEquals(LocalTime(17, 0), early.endTime)
        assertEquals(tue, early.endDate)
        val dayBefore = form.withEndDate(LocalDate(2026, 10, 26))!!
        assertEquals(LocalTime(17, 0), dayBefore.endTime)
        assertEquals(tue, dayBefore.endDate)
        val overnight = form.withEndDate(LocalDate(2026, 10, 28))!!
        assertEquals(LocalDate(2026, 10, 28), overnight.endDate)
        assertEquals(LocalTime(17, 0), overnight.endTime)

        val allDay = form.withAllDay(true)
        assertEquals(tue, allDay.withEndDate(LocalDate(2026, 10, 20))!!.endDate)
    }

    @Test
    fun `a span over 366 days is refused`() {
        val allDay = EventForm.new(tue, 9).withAllDay(true)
        assertNull(allDay.withEndDate(LocalDate(2027, 10, 29)))
        assertEquals(LocalDate(2027, 10, 28), allDay.withEndDate(LocalDate(2027, 10, 28))!!.endDate)
        assertNull(timed().withEndDate(LocalDate(2028, 1, 1)))
    }

    @Test
    fun `all day on drops the times, off restores them or sets nine to ten`() {
        val form = timed(LocalTime(22, 0), LocalTime(23, 0)).withEndDate(LocalDate(2026, 10, 28))!!.withEndTime(LocalTime(7, 30))
        val allDay = form.withAllDay(true)
        assertTrue(allDay.allDay)
        assertNull(allDay.toData().startTime)
        assertEquals(tue, allDay.startDate)
        assertEquals(LocalDate(2026, 10, 28), allDay.endDate, "the dates stay")
        val back = allDay.withAllDay(false)
        assertEquals(LocalTime(22, 0), back.startTime)
        assertEquals(LocalTime(7, 30), back.endTime)
        assertEquals(LocalDate(2026, 10, 28), back.endDate)

        val fresh = EventForm(allDay = true, startDate = tue, endDate = LocalDate(2026, 10, 30), startTime = null, endTime = null)
        val timed = fresh.withAllDay(false)
        assertEquals(LocalTime(9, 0), timed.startTime)
        assertEquals(LocalTime(10, 0), timed.endTime)
        assertEquals(tue, timed.endDate, "on the start date")
    }

    @Test
    fun `a reminder not offered after switching all day is reset`() {
        val hour = timed().withReminder(60)
        assertNull(hour.withAllDay(true).reminderMinutesBefore)
        val day = timed().withReminder(1440)
        assertEquals(1440, day.withAllDay(true).reminderMinutesBefore)
        val onDay = timed().withAllDay(true).withReminder(0)
        assertEquals(0, onDay.withAllDay(false).reminderMinutesBefore)
    }

    @Test
    fun `repeat presets use the start date, weekly follows it, until must not be before the start`() {
        val form = timed().withPreset(RepeatPreset.WEEKLY)
        assertEquals(setOf(DayOfWeek.TUESDAY), form.recurrence!!.weekdays)
        assertEquals(RepeatPreset.WEEKLY, form.preset())
        assertEquals(setOf(DayOfWeek.WEDNESDAY), form.withStartDate(LocalDate(2026, 10, 28)).recurrence!!.weekdays)
        val custom = form.withRecurrence(Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.WEEK, 2))
        assertEquals(RecurrenceMode.FIXED, custom.recurrence!!.mode, "events repeat on a schedule only")
        assertEquals(setOf(DayOfWeek.TUESDAY), custom.recurrence!!.weekdays)

        val until = form.withRepeatUntil(LocalDate(2026, 10, 20))
        val errors = EventForm.fieldErrors(until.validate())
        assertEquals(Res.string.cal_error_before_start, errors["repeatUntil"]?.res)
        assertTrue(form.withRepeatUntil(LocalDate(2027, 5, 31)).validate().isEmpty())

        val none = form.withRepeatUntil(LocalDate(2027, 5, 31)).withPreset(RepeatPreset.NONE)
        assertNull(none.recurrence)
        assertNull(none.repeatUntil)
    }

    @Test
    fun `applyTo writes only the changed fields`() {
        val stored =
            EventData(
                "Dance",
                "m1",
                tue,
                tue,
                startTime = LocalTime(17, 30),
                endTime = LocalTime(18, 45),
                place = "Hall",
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.TUESDAY)),
            )
        val form = EventForm.of(stored, today).withTitle("Dance class")
        // Another phone changed the place and the times meanwhile.
        val now = stored.copy(place = "Gym", startTime = LocalTime(18, 0), endTime = LocalTime(19, 0))
        val saved = form.applyTo(now)
        assertEquals("Dance class", saved.title)
        assertEquals("Gym", saved.place)
        assertEquals(LocalTime(18, 0), saved.startTime)
        assertFalse(form.touchesSchedule())

        val retimed = EventForm.of(stored, today).withStartTime(LocalTime(19, 0)).applyTo(now)
        assertEquals(LocalTime(19, 0), retimed.startTime)
        assertEquals(LocalTime(20, 15), retimed.endTime, "the times go together")
        assertTrue(EventForm.of(stored, today).withStartDate(LocalDate(2026, 10, 28)).touchesSchedule())
        assertTrue(EventForm.of(stored, today).withAllDay(true).touchesSchedule())
    }

    @Test
    fun `a memorable date has its fixed shape, years only with a known year`() {
        val form =
            EventForm
                .new(tue, 16, memorable = true)
                .withTitle("Wedding anniversary")
                .withPlace("Somewhere")
                .withReminder(60)
                .withMemorableDate(12, 6, 2010, today)
                .withCountYears(true)
        val data = form.toData("m1")
        assertEquals(EventKind.MEMORABLE_DATE, data.kind)
        assertTrue(data.allDay)
        assertEquals(LocalDate(2010, 6, 12), data.startDate)
        assertEquals(data.startDate, data.endDate)
        assertNull(data.startTime)
        assertNull(data.place)
        assertNull(data.reminderMinutesBefore)
        assertEquals(Validators.MEMORABLE_RECURRENCE, data.recurrence)
        assertTrue(data.countYears)
        assertTrue(Validators.event(data).isEmpty())
        assertEquals(16, form.yearsThisYear(today))

        val unknown = form.withMemorableDate(29, 2, null, today)
        assertEquals(LocalDate(2026, 2, 28), unknown.startDate, "this year's date; 29 February falls on the 28th")
        assertFalse(unknown.yearKnown)
        assertFalse(unknown.toData().countYears)
        assertFalse(unknown.withCountYears(true).countYears)
        assertNull(unknown.yearsThisYear(today))

        val reopened = EventForm.of(data, today)
        assertTrue(reopened.yearKnown)
        assertTrue(reopened.allDay)
    }

    @Test
    fun `only this sends the changed fields and keeps earlier changes of the occurrence`() {
        val series =
            EventData(
                "Dance",
                "m1",
                LocalDate(2026, 10, 6),
                LocalDate(2026, 10, 6),
                startTime = LocalTime(17, 30),
                endTime = LocalTime(18, 45),
                place = "Hall",
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.TUESDAY)),
            )
        val occ =
            app.lares.contract.calendar.EventSchedule
                .occurrence("e", series, emptyList(), tue)!!
        val plain = EventForm.ofOccurrence(series, occ, today).withTitle("Dance (open lesson)")
        val changes = plain.occurrenceChanges(null)
        assertEquals("Dance (open lesson)", changes.title)
        assertNull(changes.startDate)
        assertNull(changes.startTime)
        assertNull(changes.place)
        assertNull(changes.notes)
        assertNull(changes.participantIds)

        val row = EventOccurrenceData("e", tue, startDate = tue, endDate = tue, startTime = LocalTime(18, 0), endTime = LocalTime(19, 15))
        val movedOcc =
            app.lares.contract.calendar.EventSchedule
                .occurrence("e", series, listOf(row), tue)!!
        val placeOnly = EventForm.ofOccurrence(series, movedOcc, today).withPlace("")
        val kept = placeOnly.occurrenceChanges(row)
        assertEquals(LocalTime(18, 0), kept.startTime, "the earlier time change stays")
        assertEquals(tue, kept.startDate)
        assertEquals("", kept.place, "no place for this occurrence")

        val retimed = EventForm.ofOccurrence(series, occ, today).withStartTime(LocalTime(18, 0)).occurrenceChanges(null)
        assertEquals(tue, retimed.startDate)
        assertEquals(tue, retimed.endDate)
        assertEquals(LocalTime(18, 0), retimed.startTime)
        assertEquals(LocalTime(19, 15), retimed.endTime)
    }

    @Test
    fun `this and following starts from the occurrence with the series' rule`() {
        val series =
            EventData(
                "Holiday camp",
                "m1",
                LocalDate(2026, 10, 6),
                LocalDate(2026, 10, 7),
                allDay = true,
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.TUESDAY)),
                repeatUntil = LocalDate(2027, 5, 31),
            )
        val shifted = EventForm.shifted(series, tue)
        assertEquals(tue, shifted.startDate)
        assertEquals(LocalDate(2026, 10, 28), shifted.endDate)
        assertEquals(series.recurrence, shifted.recurrence)
        assertEquals(series.repeatUntil, shifted.repeatUntil)
    }
}
