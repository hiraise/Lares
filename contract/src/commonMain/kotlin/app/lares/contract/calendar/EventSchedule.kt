package app.lares.contract.calendar

import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.tasks.Recurrences
import app.lares.contract.tasks.TaskSchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One occurrence of an event with the overrides of its `event_occurrence` row applied (architecture/calendar.md
 * § EventSchedule). [occurrence] is the scheduled start date (one-off: the start date): the key of an occurrence
 * everywhere. Times are null for all-day occurrences.
 */
data class EventOccurrence(
    val eventId: String,
    val occurrence: LocalDate,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val title: String,
    val place: String?,
    val notes: String?,
    val participantIds: Set<String>,
    /** An override row applies. */
    val changed: Boolean,
) {
    val allDay: Boolean get() = startTime == null
}

/**
 * Occurrences of events (CAL-003, CAL-013, CAL-015, ADR-0022). Pure: callers pass dates, `now` and the household zone.
 * Scheduled dates come from [Recurrences] (the tasks rules, fixed mode). Functions taking `rows` use only the rows of
 * `eventId`; they ignore rows for dates that are not scheduled, and one-offs ignore rows altogether.
 */
object EventSchedule {
    const val MAX_SPAN_DAYS = 366

    /** Guard against a daily rule over a huge range. */
    const val MAX_PER_CALL = 2000

    /** How far [next] looks ahead; enough for yearly rules. */
    private const val NEXT_LOOKAHEAD_DAYS = 400

    /** Days from the start date to the end date. */
    fun spanDays(event: EventData): Int = event.startDate.daysUntil(event.endDate)

    /** Whether an occurrence of [event] is scheduled to start on [date] (before overrides). */
    fun isScheduled(
        event: EventData,
        date: LocalDate,
    ): Boolean {
        val rule = event.recurrence ?: return date == event.startDate
        val until = event.repeatUntil
        return event.startDate <= date &&
            (until == null || date <= until) &&
            Recurrences.firstOnOrAfter(rule, event.startDate, date) == date
    }

    /** Scheduled start dates in `[from, to]`, ascending, at most [MAX_PER_CALL]. */
    fun scheduledStarts(
        event: EventData,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val rule = event.recurrence ?: return if (event.startDate in from..to) listOf(event.startDate) else emptyList()
        val last = event.repeatUntil?.let { minOf(it, to) } ?: to
        val dates = ArrayList<LocalDate>()
        var date = Recurrences.firstOnOrAfter(rule, event.startDate, from)
        while (date <= last && dates.size < MAX_PER_CALL) {
            dates += date
            date = Recurrences.firstOnOrAfter(rule, event.startDate, date.plus(1, DateTimeUnit.DAY))
        }
        return dates
    }

    /** Occurrences that cover at least one day of `[from, to]`, sorted for display. Cancelled ones are left out. */
    fun occurrences(
        eventId: String,
        event: EventData,
        rows: List<EventOccurrenceData>,
        from: LocalDate,
        to: LocalDate,
    ): List<EventOccurrence> {
        val overrides = applicableRows(eventId, event, rows)
        val natural =
            scheduledStarts(event, from.minus(spanDays(event), DateTimeUnit.DAY), to)
                .filter { it !in overrides }
                .map { effective(eventId, event, it, null) }
        val changed =
            overrides.values
                .filter { !it.cancelled }
                .map { effective(eventId, event, it.occurrence, it) }
        return (natural + changed)
            .filter { it.startDate <= to && lastDay(it) >= from }
            .sortedWith(ORDER)
    }

    /** The occurrence scheduled on [date], or null if it is cancelled or [date] is not scheduled. */
    fun occurrence(
        eventId: String,
        event: EventData,
        rows: List<EventOccurrenceData>,
        date: LocalDate,
    ): EventOccurrence? {
        if (!isScheduled(event, date)) return null
        val row = applicableRows(eventId, event, rows)[date]
        if (row?.cancelled == true) return null
        return effective(eventId, event, date, row)
    }

    /** The first occurrence that has not ended at [now]. */
    fun next(
        eventId: String,
        event: EventData,
        rows: List<EventOccurrenceData>,
        now: Instant,
        zone: TimeZone,
    ): EventOccurrence? {
        val today = now.toLocalDateTime(zone).date
        val from = today.minus(spanDays(event), DateTimeUnit.DAY)
        return occurrences(eventId, event, rows, from, today.plus(NEXT_LOOKAHEAD_DAYS, DateTimeUnit.DAY))
            .firstOrNull { endMoment(it, zone) > now }
    }

    /** The last day the occurrence covers: a timed one ending at 00:00 on a later day ends the day before. */
    fun lastDay(occ: EventOccurrence): LocalDate =
        if (occ.endTime == LocalTime(0, 0) && occ.endDate > occ.startDate) occ.endDate.minus(1, DateTimeUnit.DAY) else occ.endDate

    /** The start of a timed occurrence; null for all-day ones. */
    fun startMoment(
        occ: EventOccurrence,
        zone: TimeZone,
    ): Instant? = occ.startTime?.let { LocalDateTime(occ.startDate, it).toInstant(zone) }

    /** The end of the occurrence; all-day ones end at 00:00 after their last day. */
    fun endMoment(
        occ: EventOccurrence,
        zone: TimeZone,
    ): Instant {
        val end = occ.endTime
        return if (end != null && occ.startTime != null) {
            LocalDateTime(occ.endDate, end).toInstant(zone)
        } else {
            LocalDateTime(lastDay(occ).plus(1, DateTimeUnit.DAY), LocalTime(0, 0)).toInstant(zone)
        }
    }

    /** When a reminder [minutesBefore] fires (CAL-006): before the start, or before 09:00 of an all-day start date. */
    fun reminderAt(
        occ: EventOccurrence,
        minutesBefore: Int,
        zone: TimeZone,
    ): Instant {
        val base = startMoment(occ, zone) ?: LocalDateTime(occ.startDate, TaskSchedule.DAY_START).toInstant(zone)
        return base - minutesBefore.minutes
    }

    /** Non-empty rows of [eventId] for scheduled dates, by date. One-offs have none. */
    private fun applicableRows(
        eventId: String,
        event: EventData,
        rows: List<EventOccurrenceData>,
    ): Map<LocalDate, EventOccurrenceData> {
        if (event.recurrence == null) return emptyMap()
        return rows
            .filter { it.eventId == eventId && !it.isEmpty() && isScheduled(event, it.occurrence) }
            .associateBy { it.occurrence }
    }

    private fun EventOccurrenceData.isEmpty(): Boolean =
        !cancelled &&
            startDate == null &&
            endDate == null &&
            startTime == null &&
            endTime == null &&
            title == null &&
            place == null &&
            notes == null &&
            participantIds == null

    private fun effective(
        eventId: String,
        event: EventData,
        date: LocalDate,
        row: EventOccurrenceData?,
    ): EventOccurrence {
        val timed = !event.allDay
        val rowStart = row?.startDate
        val rowEnd = row?.endDate
        val rowTimes =
            rowStart != null &&
                rowEnd != null &&
                if (timed) row.startTime != null && row.endTime != null else row.startTime == null && row.endTime == null
        val startDate: LocalDate
        val endDate: LocalDate
        val startTime: LocalTime?
        val endTime: LocalTime?
        if (rowTimes) {
            startDate = rowStart!!
            endDate = rowEnd!!
            startTime = row.startTime
            endTime = row.endTime
        } else {
            startDate = date
            endDate = date.plus(spanDays(event), DateTimeUnit.DAY)
            startTime = if (timed) event.startTime else null
            endTime = if (timed) event.endTime else null
        }
        return EventOccurrence(
            eventId = eventId,
            occurrence = date,
            startDate = startDate,
            endDate = endDate,
            startTime = startTime,
            endTime = endTime,
            title = row?.title?.ifBlank { null } ?: event.title,
            place = (row?.place ?: event.place)?.ifEmpty { null },
            notes = (row?.notes ?: event.notes)?.ifEmpty { null },
            participantIds = row?.participantIds ?: event.participantIds,
            changed = row != null,
        )
    }

    /** Display order: start date, all-day first, start time, title, event id, scheduled date. */
    val ORDER: Comparator<EventOccurrence> =
        compareBy<EventOccurrence> { it.startDate }
            .thenBy { !it.allDay }
            .thenBy { it.startTime }
            .thenBy { it.title }
            .thenBy { it.eventId }
            .thenBy { it.occurrence }
}
