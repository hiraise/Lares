package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every row of the validation table in architecture/calendar.md § Validation, plus the normalizers. */
class EventValidatorsTest {
    private val day = LocalDate(2026, 10, 28)
    private val valid = EventData("Dentist", "m1", day, day, startTime = LocalTime(16, 0), endTime = LocalTime(17, 0))
    private val trip = EventData("Trip", "m1", day, LocalDate(2026, 10, 30), allDay = true)
    private val yearly = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR)
    private val memorable =
        EventData(
            "Wedding",
            "m1",
            LocalDate(2010, 6, 12),
            LocalDate(2010, 6, 12),
            allDay = true,
            recurrence = yearly,
            kind = EventKind.MEMORABLE_DATE,
            countYears = true,
        )

    private fun errors(data: EventData) = Validators.event(data)

    private fun ids(n: Int) = (1..n).map { "x$it" }.toSet()

    @Test
    fun `CAL-001 valid timed, all-day and memorable events pass`() {
        assertEquals(emptyMap(), errors(valid))
        assertEquals(emptyMap(), errors(trip))
        assertEquals(emptyMap(), errors(memorable))
        val series =
            valid.copy(
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.WEDNESDAY)),
                repeatUntil = day,
                reminderMinutesBefore = 0,
                participantIds = ids(30),
                tagIds = ids(20),
            )
        assertEquals(emptyMap(), errors(series))
    }

    @Test
    fun `CAL-001 title required and limited`() {
        assertEquals(mapOf("title" to FieldErrorCode.REQUIRED), errors(valid.copy(title = "  ")))
        assertEquals(mapOf("title" to FieldErrorCode.TOO_LONG), errors(valid.copy(title = "a".repeat(201))))
    }

    @Test
    fun `CAL-001 times required when timed, absent when all-day, whole minutes`() {
        assertEquals(
            mapOf("startTime" to FieldErrorCode.REQUIRED, "endTime" to FieldErrorCode.REQUIRED),
            errors(valid.copy(startTime = null, endTime = null)),
        )
        assertEquals(mapOf("startTime" to FieldErrorCode.INVALID_FORMAT), errors(trip.copy(startTime = LocalTime(9, 0))))
        assertEquals(mapOf("endTime" to FieldErrorCode.INVALID_FORMAT), errors(valid.copy(endTime = LocalTime(17, 0, 30))))
    }

    @Test
    fun `CAL-013 end date out of range`() {
        val outOfRange = mapOf("endDate" to FieldErrorCode.OUT_OF_RANGE)
        assertEquals(outOfRange, errors(trip.copy(endDate = LocalDate(2026, 10, 27))))
        assertEquals(outOfRange, errors(valid.copy(endTime = LocalTime(16, 0))))
        assertEquals(outOfRange, errors(valid.copy(endTime = LocalTime(15, 0))))
        assertEquals(emptyMap(), errors(valid.copy(endDate = LocalDate(2026, 10, 29), endTime = LocalTime(1, 0))))
        assertEquals(emptyMap(), errors(trip.copy(endDate = LocalDate(2027, 10, 29))))
        assertEquals(outOfRange, errors(trip.copy(endDate = LocalDate(2027, 10, 30))))
    }

    @Test
    fun `CAL-001 place, notes, participants and tags limits`() {
        assertEquals(mapOf("place" to FieldErrorCode.TOO_LONG), errors(valid.copy(place = "a".repeat(201))))
        assertEquals(mapOf("notes" to FieldErrorCode.TOO_LONG), errors(valid.copy(notes = "a".repeat(2001))))
        assertEquals(mapOf("participantIds" to FieldErrorCode.OUT_OF_RANGE), errors(valid.copy(participantIds = ids(31))))
        assertEquals(mapOf("tagIds" to FieldErrorCode.OUT_OF_RANGE), errors(valid.copy(tagIds = ids(21))))
    }

    @Test
    fun `CAL-003 recurrence and repeat until`() {
        assertEquals(
            mapOf("recurrence" to FieldErrorCode.INVALID_FORMAT),
            errors(valid.copy(recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK))),
        )
        assertEquals(
            mapOf("recurrence" to FieldErrorCode.OUT_OF_RANGE),
            errors(valid.copy(recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY, 0))),
        )
        assertEquals(
            mapOf("recurrence" to FieldErrorCode.INVALID_FORMAT),
            errors(valid.copy(recurrence = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.DAY))),
        )
        assertEquals(mapOf("repeatUntil" to FieldErrorCode.INVALID_FORMAT), errors(valid.copy(repeatUntil = day)))
        assertEquals(
            mapOf("repeatUntil" to FieldErrorCode.OUT_OF_RANGE),
            errors(valid.copy(recurrence = yearly, repeatUntil = LocalDate(2026, 10, 27))),
        )
    }

    @Test
    fun `CAL-006 reminder minutes in range`() {
        assertEquals(mapOf("reminderMinutesBefore" to FieldErrorCode.OUT_OF_RANGE), errors(valid.copy(reminderMinutesBefore = -1)))
        assertEquals(mapOf("reminderMinutesBefore" to FieldErrorCode.OUT_OF_RANGE), errors(valid.copy(reminderMinutesBefore = 43201)))
        assertEquals(emptyMap(), errors(valid.copy(reminderMinutesBefore = 43200)))
        assertEquals(emptyMap(), Validators.eventReminder(EventReminderData("e1", "m1", null)))
        assertEquals(emptyMap(), Validators.eventReminder(EventReminderData("e1", "m1", 43200)))
        assertEquals(mapOf("minutesBefore" to FieldErrorCode.OUT_OF_RANGE), Validators.eventReminder(EventReminderData("e1", "m1", -5)))
    }

    @Test
    fun `CAL-005 memorable shape reports each offending field once`() {
        val broken =
            memorable.copy(
                allDay = false,
                endDate = LocalDate(2010, 6, 13),
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR, 2),
                repeatUntil = LocalDate(2030, 1, 1),
                reminderMinutesBefore = 60,
            )
        assertEquals(
            mapOf(
                "allDay" to FieldErrorCode.INVALID_FORMAT,
                "endDate" to FieldErrorCode.INVALID_FORMAT,
                "recurrence" to FieldErrorCode.INVALID_FORMAT,
                "repeatUntil" to FieldErrorCode.INVALID_FORMAT,
                "reminderMinutesBefore" to FieldErrorCode.INVALID_FORMAT,
            ),
            errors(broken),
        )
        assertEquals(mapOf("recurrence" to FieldErrorCode.INVALID_FORMAT), errors(memorable.copy(recurrence = null)))
        assertEquals(mapOf("countYears" to FieldErrorCode.INVALID_FORMAT), errors(valid.copy(countYears = true)))
    }

    @Test
    fun `CAL-015 occurrence times are all null or the full set`() {
        val row = EventOccurrenceData("e1", day)
        assertEquals(emptyMap(), Validators.eventOccurrence(row, valid))
        assertEquals(emptyMap(), Validators.eventOccurrence(row.copy(cancelled = true), valid))
        val timedFull = row.copy(startDate = day, endDate = day, startTime = LocalTime(18, 0), endTime = LocalTime(19, 0))
        assertEquals(emptyMap(), Validators.eventOccurrence(timedFull, valid))
        assertEquals(
            mapOf("startTime" to FieldErrorCode.INVALID_FORMAT, "endTime" to FieldErrorCode.INVALID_FORMAT),
            Validators.eventOccurrence(row.copy(startDate = day, endDate = day), valid),
        )
        assertEquals(
            mapOf("endDate" to FieldErrorCode.INVALID_FORMAT),
            Validators.eventOccurrence(timedFull.copy(endDate = null), valid),
        )
        assertEquals(
            mapOf("startDate" to FieldErrorCode.INVALID_FORMAT, "endDate" to FieldErrorCode.INVALID_FORMAT),
            Validators.eventOccurrence(row.copy(startTime = LocalTime(9, 0), endTime = LocalTime(10, 0)), valid),
        )
        val allDayFull = row.copy(startDate = day, endDate = LocalDate(2026, 10, 31))
        assertEquals(emptyMap(), Validators.eventOccurrence(allDayFull, trip))
        assertEquals(
            mapOf("startTime" to FieldErrorCode.INVALID_FORMAT),
            Validators.eventOccurrence(allDayFull.copy(startTime = LocalTime(9, 0)), trip),
        )
        assertEquals(
            mapOf("endDate" to FieldErrorCode.OUT_OF_RANGE),
            Validators.eventOccurrence(timedFull.copy(endTime = LocalTime(17, 0)), valid),
        )
        assertEquals(
            mapOf("endDate" to FieldErrorCode.OUT_OF_RANGE),
            Validators.eventOccurrence(allDayFull.copy(endDate = LocalDate(2026, 10, 27)), trip),
        )
    }

    @Test
    fun `CAL-015 occurrence texts and participants limits`() {
        val row = EventOccurrenceData("e1", day)
        assertEquals(mapOf("title" to FieldErrorCode.TOO_LONG), Validators.eventOccurrence(row.copy(title = "a".repeat(201)), valid))
        assertEquals(mapOf("place" to FieldErrorCode.TOO_LONG), Validators.eventOccurrence(row.copy(place = "a".repeat(201)), valid))
        assertEquals(mapOf("notes" to FieldErrorCode.TOO_LONG), Validators.eventOccurrence(row.copy(notes = "a".repeat(2001)), valid))
        assertEquals(
            mapOf("participantIds" to FieldErrorCode.OUT_OF_RANGE),
            Validators.eventOccurrence(row.copy(participantIds = ids(31)), valid),
        )
        assertEquals(emptyMap(), Validators.eventOccurrence(row.copy(place = "", notes = "", participantIds = emptySet()), valid))
    }

    @Test
    fun `normalizers trim texts and drop times of all-day events`() {
        val event =
            Validators.normalizeEvent(
                trip.copy(title = "  Trip ", place = "  ", notes = " Sea ", startTime = LocalTime(9, 0), endTime = LocalTime(10, 0)),
            )
        assertEquals("Trip", event.title)
        assertNull(event.place)
        assertEquals("Sea", event.notes)
        assertNull(event.startTime)
        assertNull(event.endTime)
        assertEquals(LocalTime(16, 0), Validators.normalizeEvent(valid).startTime)
        val row = Validators.normalizeEventOccurrence(EventOccurrenceData("e1", day, title = "  ", place = "  ", notes = " Cap "))
        assertNull(row.title)
        assertEquals("", row.place)
        assertEquals("Cap", row.notes)
        assertEquals("Gala", Validators.normalizeEventOccurrence(EventOccurrenceData("e1", day, title = " Gala ")).title)
    }
}
