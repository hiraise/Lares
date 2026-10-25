package app.lares.server.calendar

import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** CAL-007: the pure parts of event notifications — the `{when}` text and the change helpers (plan M3-03). */
class EventChangesTest {
    private val zone = TimeZone.of("Europe/Moscow")
    private val now = Instant.parse("2026-10-22T10:00:00Z")
    private val weekly = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1)

    private fun event(
        startDate: LocalDate = LocalDate(2026, 10, 27),
        startTime: LocalTime? = LocalTime(16, 0),
        recurrence: Recurrence? = null,
        repeatUntil: LocalDate? = null,
        participantIds: Set<String> = emptySet(),
    ) = EventData(
        title = "Swimming",
        createdBy = UUID(0, 1).toString(),
        startDate = startDate,
        endDate = startDate,
        allDay = startTime == null,
        startTime = startTime,
        endTime = startTime?.let { LocalTime(it.hour + 1, 0) },
        participantIds = participantIds,
        recurrence = recurrence,
        repeatUntil = repeatUntil,
    )

    private fun occ(data: EventData): EventOccurrence = EventSchedule.occurrence("e", data, emptyList(), data.startDate)!!

    @Test
    fun cal007WhenTextRuAndEn() {
        val timed = occ(event())
        assertEquals("вт, 27 окт., 16:00", EventWhenText.format("ru", timed))
        assertEquals("Tue, Oct 27, 16:00", EventWhenText.format("en", timed))
        val allDay = occ(event(LocalDate(2026, 5, 1), startTime = null))
        assertEquals("пт, 1 мая", EventWhenText.format("ru", allDay))
        assertEquals("Fri, May 1", EventWhenText.format("en", allDay))
        val morning = occ(event(LocalDate(2026, 9, 6), startTime = LocalTime(9, 5)))
        assertEquals("вс, 6 сент., 09:05", EventWhenText.format("ru", morning))
        assertEquals("Sun, Sep 6, 09:05", EventWhenText.format("en", morning))
    }

    @Test
    fun cal007MemberParticipantsEmptyMeansEveryMember() {
        val a = UUID(1, 1)
        val b = UUID(1, 2)
        val members = mapOf("pa" to a, "pb" to b)
        assertEquals(setOf(a, b), EventChanges.memberParticipants(emptySet(), members))
        assertEquals(setOf(b), EventChanges.memberParticipants(setOf("pb", "grandma"), members))
        assertEquals(emptySet(), EventChanges.memberParticipants(setOf("grandma"), members))
    }

    @Test
    fun cal007ScheduleChangesAndSeriesCuts() {
        val series = event(recurrence = weekly)
        assertFalse(EventChanges.scheduleChanged(series, series.copy(title = "Swim", notes = "Towel", tagIds = setOf("t"))))
        assertTrue(EventChanges.scheduleChanged(series, series.copy(place = "Pool")))
        assertTrue(EventChanges.scheduleChanged(series, series.copy(repeatUntil = LocalDate(2026, 12, 1))))

        val cut = series.copy(repeatUntil = LocalDate(2026, 11, 10))
        assertTrue(EventChanges.onlyUntilEarlier(series, cut))
        assertTrue(EventChanges.onlyUntilEarlier(cut, cut.copy(repeatUntil = LocalDate(2026, 11, 3))))
        assertFalse(EventChanges.onlyUntilEarlier(cut, series), "removing the end")
        assertFalse(EventChanges.onlyUntilEarlier(cut, cut.copy(repeatUntil = LocalDate(2026, 12, 1))), "later")
        assertFalse(EventChanges.onlyUntilEarlier(series, cut.copy(place = "Gym")), "another field too")

        // The first occurrence the cut removes; nothing when the series had already ended.
        val first = EventChanges.cutOccurrence("e", series, emptyList(), LocalDate(2026, 11, 10), now, zone)
        assertEquals(LocalDate(2026, 11, 17), first?.occurrence)
        val past = event(LocalDate(2026, 1, 6), recurrence = weekly, repeatUntil = LocalDate(2026, 3, 1))
        assertNull(EventChanges.cutOccurrence("e", past, emptyList(), LocalDate(2026, 2, 1), now, zone))
    }
}
