package app.lares.contract.entities

import app.lares.contract.api.LaresJson
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarEntitiesTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val text = LaresJson.encodeToString(serializer, value)
        assertEquals(value, LaresJson.decodeFromString(serializer, text), text)
        return text
    }

    @Test
    fun `CAL-001 CAL-003 event round trip with ISO dates, times, rule and kind`() {
        val event =
            EventData(
                title = "Swimming",
                createdBy = "m1",
                startDate = LocalDate(2026, 10, 26),
                endDate = LocalDate(2026, 10, 26),
                startTime = LocalTime(16, 0),
                endTime = LocalTime(17, 30),
                place = "Pool",
                notes = "Towel",
                participantIds = setOf("p1", "p2"),
                tagIds = setOf("t1"),
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
                repeatUntil = LocalDate(2026, 12, 31),
                reminderMinutesBefore = 30,
                splitFrom = "e0",
            )
        val text = roundTrip(EventData.serializer(), event)
        assertTrue("\"startDate\":\"2026-10-26\"" in text, text)
        assertTrue("\"startTime\":\"16:00\"" in text, text)
        val memorable =
            EventData(
                title = "Wedding",
                createdBy = "m1",
                startDate = LocalDate(2010, 6, 12),
                endDate = LocalDate(2010, 6, 12),
                allDay = true,
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR),
                kind = EventKind.MEMORABLE_DATE,
                countYears = true,
            )
        assertTrue("\"kind\":\"memorable_date\"" in roundTrip(EventData.serializer(), memorable))
    }

    @Test
    fun `CAL-015 occurrence and reminder rows round trip`() {
        roundTrip(
            EventOccurrenceData.serializer(),
            EventOccurrenceData(
                eventId = "e1",
                occurrence = LocalDate(2026, 10, 28),
                startDate = LocalDate(2026, 10, 29),
                endDate = LocalDate(2026, 10, 29),
                startTime = LocalTime(18, 0),
                endTime = LocalTime(19, 0),
                title = "Moved",
                place = "",
                notes = "",
                participantIds = emptySet(),
            ),
        )
        roundTrip(EventOccurrenceData.serializer(), EventOccurrenceData("e1", LocalDate(2026, 10, 28), cancelled = true))
        roundTrip(EventReminderData.serializer(), EventReminderData("e1", "m1", 15))
        roundTrip(EventReminderData.serializer(), EventReminderData("e1", "m1", null))
    }

    @Test
    fun `CAL-001 missing optional fields take their defaults`() {
        val event =
            LaresJson.decodeFromString(
                EventData.serializer(),
                """{"title":"Dentist","createdBy":"m1","startDate":"2026-10-28","endDate":"2026-10-28"}""",
            )
        assertEquals(EventData("Dentist", "m1", LocalDate(2026, 10, 28), LocalDate(2026, 10, 28)), event)
        assertEquals(EventKind.EVENT, event.kind)
        val row = LaresJson.decodeFromString(EventOccurrenceData.serializer(), """{"eventId":"e1","occurrence":"2026-10-28"}""")
        assertEquals(EventOccurrenceData("e1", LocalDate(2026, 10, 28)), row)
        assertNull(row.participantIds)
        val reminder = LaresJson.decodeFromString(EventReminderData.serializer(), """{"eventId":"e1","memberId":"m1"}""")
        assertNull(reminder.minutesBefore)
    }

    @Test
    fun `CAL-005 unknown kind decodes to event, unknown rule unit to no rule`() {
        val event =
            LaresJson.decodeFromString(
                EventData.serializer(),
                """{"title":"x","createdBy":"m1","startDate":"2026-10-28","endDate":"2026-10-28","kind":"holiday",
                   "recurrence":{"mode":"fixed","unit":"fortnight"}}""",
            )
        assertEquals(EventKind.EVENT, event.kind)
        assertNull(event.recurrence)
        assertEquals("memorable_date", EventKind.MEMORABLE_DATE.key)
        assertEquals(EventKind.MEMORABLE_DATE, EventKind.fromKey("memorable_date"))
        assertNull(EventKind.fromKey("holiday"))
    }
}
