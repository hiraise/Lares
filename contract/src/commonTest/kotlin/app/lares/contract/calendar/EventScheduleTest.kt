package app.lares.contract.calendar

import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventScheduleTest {
    private val zone = TimeZone.of("Europe/Moscow")
    private val id = "e1"

    private fun d(
        month: Int,
        day: Int,
        year: Int = 2026,
    ) = LocalDate(year, month, day)

    private fun t(
        hour: Int,
        minute: Int = 0,
    ) = LocalTime(hour, minute)

    private fun at(
        date: LocalDate,
        time: LocalTime,
    ) = LocalDateTime(date, time).toInstant(zone)

    private fun rule(
        unit: RecurrenceUnit,
        interval: Int = 1,
        vararg weekdays: DayOfWeek,
    ) = Recurrence(RecurrenceMode.FIXED, unit, interval, weekdays.toSet())

    private fun timed(
        start: LocalDate,
        from: LocalTime = t(16),
        to: LocalTime = t(17),
        end: LocalDate = start,
        recurrence: Recurrence? = null,
        until: LocalDate? = null,
        title: String = "Swimming",
    ) = EventData(title, "m1", start, end, startTime = from, endTime = to, recurrence = recurrence, repeatUntil = until)

    private fun allDay(
        start: LocalDate,
        end: LocalDate = start,
        recurrence: Recurrence? = null,
        title: String = "Trip",
    ) = EventData(title, "m1", start, end, allDay = true, recurrence = recurrence)

    private fun starts(
        event: EventData,
        from: LocalDate,
        to: LocalDate,
        rows: List<EventOccurrenceData> = emptyList(),
    ) = EventSchedule.occurrences(id, event, rows, from, to).map { it.startDate }

    /** Mondays and Wednesdays from Mon 2026-10-26 until 2026-11-09. */
    private val weekly =
        timed(d(10, 26), recurrence = rule(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), until = d(11, 9))

    @Test
    fun `CAL-001 a one-off occurs once on its start date`() {
        val event = timed(d(10, 28))
        assertEquals(listOf(d(10, 28)), starts(event, d(10, 1), d(10, 31)))
        assertEquals(emptyList(), starts(event, d(10, 29), d(10, 31)))
        assertTrue(EventSchedule.isScheduled(event, d(10, 28)))
        assertFalse(EventSchedule.isScheduled(event, d(10, 29)))
    }

    @Test
    fun `CAL-003 weekly on Monday and Wednesday until an end date`() {
        val expected = listOf(d(10, 26), d(10, 28), d(11, 2), d(11, 4), d(11, 9))
        assertEquals(expected, EventSchedule.scheduledStarts(weekly, d(10, 1), d(12, 31)))
        assertEquals(expected, starts(weekly, d(10, 1), d(12, 31)))
        assertFalse(EventSchedule.isScheduled(weekly, d(11, 11)))
        assertFalse(EventSchedule.isScheduled(weekly, d(10, 27)))
    }

    @Test
    fun `CAL-003 monthly on the 31st falls on the last day of shorter months`() {
        val event = allDay(d(1, 31), recurrence = rule(RecurrenceUnit.MONTH))
        assertEquals(listOf(d(1, 31), d(2, 28), d(3, 31), d(4, 30)), starts(event, d(1, 1), d(4, 30)))
    }

    @Test
    fun `CAL-003 yearly on 29 February falls on 28 February in other years`() {
        val event = allDay(d(2, 29, 2024), recurrence = rule(RecurrenceUnit.YEAR))
        assertEquals(
            listOf(d(2, 28, 2025), d(2, 28, 2026), d(2, 28, 2027), d(2, 29, 2028)),
            starts(event, d(1, 1, 2025), d(12, 31, 2028)),
        )
    }

    @Test
    fun `CAL-013 a three-day all-day event shows in a range starting on its second day`() {
        val event = allDay(d(10, 27), d(10, 29))
        assertEquals(listOf(d(10, 27)), starts(event, d(10, 28), d(10, 31)))
        assertEquals(emptyList(), starts(event, d(10, 30), d(10, 31)))
        val repeating = allDay(d(10, 27), d(10, 29), recurrence = rule(RecurrenceUnit.WEEK, 1, DayOfWeek.TUESDAY))
        assertEquals(listOf(d(10, 27), d(11, 3)), starts(repeating, d(10, 29), d(11, 3)))
    }

    @Test
    fun `CAL-013 overnight timed event covers two days, one ending at midnight covers one`() {
        val overnight = timed(d(10, 28), t(23), t(1), end = d(10, 29))
        assertEquals(listOf(d(10, 28)), starts(overnight, d(10, 29), d(10, 29)))
        val midnight = timed(d(10, 28), t(22), t(0), end = d(10, 29))
        assertEquals(emptyList(), starts(midnight, d(10, 29), d(10, 29)))
        val occ = EventSchedule.occurrences(id, midnight, emptyList(), d(10, 28), d(10, 28)).single()
        assertEquals(d(10, 28), EventSchedule.lastDay(occ))
        assertEquals(at(d(10, 29), t(0)), EventSchedule.endMoment(occ, zone))
    }

    @Test
    fun `CAL-015 a moved occurrence leaves its date and appears in the new range`() {
        val moved =
            EventOccurrenceData(id, d(10, 28), startDate = d(11, 20), endDate = d(11, 20), startTime = t(18), endTime = t(19))
        assertEquals(listOf(d(10, 26)), starts(weekly, d(10, 26), d(10, 31), listOf(moved)))
        val occ = EventSchedule.occurrences(id, weekly, listOf(moved), d(11, 20), d(11, 20)).single()
        assertEquals(d(10, 28), occ.occurrence)
        assertEquals(t(18), occ.startTime)
        assertTrue(occ.changed)
        assertEquals(occ, EventSchedule.occurrence(id, weekly, listOf(moved), d(10, 28)))
    }

    @Test
    fun `CAL-015 a cancelled occurrence disappears`() {
        val cancelled = EventOccurrenceData(id, d(10, 28), cancelled = true)
        assertEquals(listOf(d(10, 26), d(11, 2)), starts(weekly, d(10, 26), d(11, 2), listOf(cancelled)))
        assertNull(EventSchedule.occurrence(id, weekly, listOf(cancelled), d(10, 28)))
    }

    @Test
    fun `CAL-015 an empty row changes nothing, a row for an unscheduled date or another event is ignored`() {
        val plain = EventSchedule.occurrences(id, weekly, emptyList(), d(10, 1), d(12, 31))
        val empty = EventOccurrenceData(id, d(10, 28))
        val unscheduled = EventOccurrenceData(id, d(10, 27), startDate = d(10, 30), endDate = d(10, 30), startTime = t(9), endTime = t(10))
        val foreign = EventOccurrenceData("e2", d(10, 28), cancelled = true)
        assertEquals(plain, EventSchedule.occurrences(id, weekly, listOf(empty, unscheduled, foreign), d(10, 1), d(12, 31)))
        assertFalse(plain.any { it.changed })
        val oneOff = timed(d(10, 28))
        val onOneOff = EventOccurrenceData(id, d(10, 28), cancelled = true)
        assertEquals(listOf(d(10, 28)), starts(oneOff, d(10, 28), d(10, 28), listOf(onOneOff)))
    }

    @Test
    fun `CAL-015 overrides of title, place, notes and participants`() {
        val event = weekly.copy(place = "Pool", notes = "Towel", participantIds = setOf("p1"))
        val row = EventOccurrenceData(id, d(10, 28), title = "Swimming (gala)", place = "", notes = "Cap", participantIds = setOf("p2"))
        val occ = EventSchedule.occurrence(id, event, listOf(row), d(10, 28))!!
        assertEquals("Swimming (gala)", occ.title)
        assertNull(occ.place)
        assertEquals("Cap", occ.notes)
        assertEquals(setOf("p2"), occ.participantIds)
        assertEquals(t(16), occ.startTime)
        assertEquals(d(10, 28), occ.startDate)
        val natural = EventSchedule.occurrence(id, event, listOf(row), d(10, 26))!!
        assertEquals("Pool", natural.place)
        assertFalse(natural.changed)
    }

    @Test
    fun `CAL-015 inconsistent row times are ignored but other overrides apply`() {
        val missingTimes = EventOccurrenceData(id, d(10, 28), startDate = d(10, 29), endDate = d(10, 29), title = "Late")
        val occ = EventSchedule.occurrence(id, weekly, listOf(missingTimes), d(10, 28))!!
        assertEquals(d(10, 28), occ.startDate)
        assertEquals(t(16), occ.startTime)
        assertEquals("Late", occ.title)
        val trips = allDay(d(10, 26), recurrence = rule(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY))
        val withTimes = EventOccurrenceData(id, d(11, 2), startDate = d(11, 3), endDate = d(11, 3), startTime = t(9), endTime = t(10))
        assertEquals(d(11, 2), EventSchedule.occurrence(id, trips, listOf(withTimes), d(11, 2))!!.startDate)
        val moved = EventOccurrenceData(id, d(11, 2), startDate = d(11, 3), endDate = d(11, 4))
        val occMoved = EventSchedule.occurrence(id, trips, listOf(moved), d(11, 2))!!
        assertEquals(d(11, 3), occMoved.startDate)
        assertEquals(d(11, 4), occMoved.endDate)
        assertNull(occMoved.startTime)
    }

    @Test
    fun `CAL-003 a daily rule over 20 years is capped`() {
        val daily = timed(d(1, 1), recurrence = rule(RecurrenceUnit.DAY))
        assertEquals(EventSchedule.MAX_PER_CALL, EventSchedule.scheduledStarts(daily, d(1, 1), d(1, 1, 2046)).size)
    }

    @Test
    fun `CAL-006 next skips an occurrence that has ended`() {
        val during = at(d(10, 26), t(16, 30))
        assertEquals(d(10, 26), EventSchedule.next(id, weekly, emptyList(), during, zone)!!.occurrence)
        val after = at(d(10, 26), t(17))
        assertEquals(d(10, 28), EventSchedule.next(id, weekly, emptyList(), after, zone)!!.occurrence)
        val cancelled = EventOccurrenceData(id, d(10, 28), cancelled = true)
        assertEquals(d(11, 2), EventSchedule.next(id, weekly, listOf(cancelled), after, zone)!!.occurrence)
        assertNull(EventSchedule.next(id, weekly, emptyList(), at(d(11, 9), t(18)), zone))
        val trip = allDay(d(10, 27), d(10, 29))
        assertEquals(d(10, 27), EventSchedule.next(id, trip, emptyList(), at(d(10, 29), t(23, 59)), zone)!!.occurrence)
        assertNull(EventSchedule.next(id, trip, emptyList(), at(d(10, 30), t(0)), zone))
        val birthday = allDay(d(3, 1, 2000), recurrence = rule(RecurrenceUnit.YEAR))
        assertEquals(d(3, 1, 2027), EventSchedule.next(id, birthday, emptyList(), during, zone)!!.occurrence)
    }

    @Test
    fun `CAL-006 reminder time of timed and all-day occurrences`() {
        val timedOcc = EventSchedule.occurrence(id, weekly, emptyList(), d(10, 26))!!
        assertEquals(at(d(10, 26), t(15, 30)), EventSchedule.reminderAt(timedOcc, 30, zone))
        assertEquals(at(d(10, 26), t(16)), EventSchedule.startMoment(timedOcc, zone))
        val allDayOcc = EventSchedule.occurrence(id, allDay(d(10, 27), d(10, 29)), emptyList(), d(10, 27))!!
        assertEquals(at(d(10, 27), t(9)), EventSchedule.reminderAt(allDayOcc, 0, zone))
        assertEquals(at(d(10, 26), t(9)), EventSchedule.reminderAt(allDayOcc, 24 * 60, zone))
        assertNull(EventSchedule.startMoment(allDayOcc, zone))
        assertEquals(at(d(10, 30), t(0)), EventSchedule.endMoment(allDayOcc, zone))
    }

    @Test
    fun `CAL-008 sort by start date, all-day first, start time, title, event id`() {
        val day = d(10, 28)
        val events =
            listOf(
                "e5" to timed(day, t(9), t(10), title = "B"),
                "e4" to timed(day, t(9), t(10), title = "A"),
                "e3" to timed(day, t(8), t(9), title = "Z"),
                "e2" to allDay(day, title = "Trip"),
                "e6" to timed(day, t(9), t(10), title = "A"),
                "e1" to timed(d(10, 27), t(20), t(21), title = "Earlier"),
            )
        val expected = listOf("e1", "e2", "e3", "e4", "e6", "e5")
        val viaSchedule =
            events
                .flatMap { (eventId, event) -> EventSchedule.occurrences(eventId, event, emptyList(), d(10, 27), day) }
                .sortedWith(EventSchedule.ORDER)
                .map { it.eventId }
        assertEquals(expected, viaSchedule)
    }
}
