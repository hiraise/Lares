package app.lares.client.ui.calendar

import app.lares.client.data.calendar.OccurrenceChanges
import app.lares.client.resources.Res
import app.lares.client.resources.cal_error_before_start
import app.lares.client.resources.cal_error_too_long
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.RepeatPreset
import app.lares.client.ui.common.Repeats
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.validation.Validators
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

/** The times of a timed event before «Весь день» was switched on in this edit session. */
data class RememberedTimes(
    val start: LocalTime,
    val minutes: Int,
)

/**
 * The editable fields of the event editor (plan M3-06, CAL-001…CAL-006, CAL-013), pure: no clock, no database.
 * [original] is the event (or the occurrence, or the series moved to the occurrence) as opened; [applyTo] writes only
 * the fields the user changed, so changes made on another phone meanwhile survive. Times are kept for both kinds;
 * [toData] gives a memorable date its fixed shape.
 */
data class EventForm(
    val kind: EventKind = EventKind.EVENT,
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = EPOCH,
    val startTime: LocalTime? = NINE,
    val endDate: LocalDate = EPOCH,
    val endTime: LocalTime? = TEN,
    val recurrence: Recurrence? = null,
    val repeatUntil: LocalDate? = null,
    /** Empty = the whole family (CAL-002). */
    val participantIds: Set<String> = emptySet(),
    val place: String = "",
    val notes: String = "",
    val tagIds: Set<String> = emptySet(),
    val reminderMinutesBefore: Int? = null,
    val countYears: Boolean = false,
    /** Memorable date: false = the year is unknown, [startDate] then holds this year's date. */
    val yearKnown: Boolean = true,
    val remembered: RememberedTimes? = null,
    val original: EventData? = null,
) {
    val memorable: Boolean get() = kind == EventKind.MEMORABLE_DATE

    val repeating: Boolean get() = recurrence != null

    fun withKind(value: EventKind) = copy(kind = value)

    fun withTitle(value: String) = copy(title = value.take(Validators.EVENT_TITLE_MAX))

    fun withPlace(value: String) = copy(place = value.take(Validators.EVENT_PLACE_MAX))

    fun withNotes(value: String) = copy(notes = value.take(Validators.NOTES_MAX))

    /** A new start date keeps the length; a weekly rule on the old start's weekday follows it. */
    fun withStartDate(date: LocalDate): EventForm {
        val follows = Repeats.follow(recurrence, startDate, date)
        if (allDay) return copy(startDate = date, endDate = date.plus(startDate.daysUntil(endDate), DateTimeUnit.DAY), recurrence = follows)
        val start = LocalDateTime(date, startTime ?: NINE)
        return withTimes(start, plusMinutes(start, durationMinutes())).copy(recurrence = follows)
    }

    /** A new start time keeps the length (timed events only). */
    fun withStartTime(time: LocalTime): EventForm {
        if (allDay) return this
        val start = LocalDateTime(startDate, time)
        return withTimes(start, plusMinutes(start, durationMinutes()))
    }

    /**
     * A new end date. Before the start it becomes start + 1 h (timed) or the start date (all-day); a span over
     * [EventSchedule.MAX_SPAN_DAYS] days is refused (null: «Слишком длинное событие»).
     */
    fun withEndDate(date: LocalDate): EventForm? {
        if (tooLong(startDate, date)) return null
        if (allDay) return copy(endDate = if (date < startDate) startDate else date)
        return withEnd(LocalDateTime(date, endTime ?: TEN))
    }

    /** A new end time; not after the start: start + 1 h. */
    fun withEndTime(time: LocalTime): EventForm = if (allDay) this else withEnd(LocalDateTime(endDate, time))

    /**
     * «Весь день» on keeps the dates and drops the times (remembered for this session); off sets the remembered times
     * on the start date, else 09:00–10:00. A reminder that is not offered for the new choice is reset to none.
     */
    fun withAllDay(on: Boolean): EventForm {
        if (on == allDay) return this
        val next =
            if (on) {
                val start = startTime ?: NINE
                copy(allDay = true, startTime = null, endTime = null, remembered = RememberedTimes(start, durationMinutes()))
            } else {
                val times = remembered ?: RememberedTimes(NINE, MINUTES_PER_HOUR)
                val start = LocalDateTime(startDate, times.start)
                copy(allDay = false).withTimes(start, plusMinutes(start, times.minutes))
            }
        val reminder = reminderMinutesBefore
        val fits = reminder == null || reminder in CalendarLogic.reminderOptions(on)
        return if (fits) next else next.copy(reminderMinutesBefore = null)
    }

    /** A repeat rule (fixed only); a weekly rule without weekdays repeats on the start's weekday. Null stops it. */
    fun withRecurrence(rule: Recurrence?): EventForm =
        if (rule == null) {
            copy(recurrence = null, repeatUntil = null)
        } else {
            copy(recurrence = Repeats.fit(rule.copy(mode = RecurrenceMode.FIXED), startDate))
        }

    fun withPreset(preset: RepeatPreset): EventForm =
        when (preset) {
            RepeatPreset.NONE -> withRecurrence(null)
            RepeatPreset.CUSTOM -> this
            else -> withRecurrence(Repeats.presetRule(preset, startDate))
        }

    fun preset(): RepeatPreset = Repeats.preset(recurrence, startDate)

    /** «До»: the last date an occurrence may start; null = «Без конца». */
    fun withRepeatUntil(date: LocalDate?) = copy(repeatUntil = if (recurrence == null) null else date)

    /** «Вся семья» clears the participants. */
    fun withWholeFamily() = copy(participantIds = emptySet())

    fun toggleParticipant(personId: String) =
        copy(participantIds = if (personId in participantIds) participantIds - personId else participantIds + personId)

    fun toggleTag(tagId: String) = copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)

    fun withReminder(minutesBefore: Int?) = copy(reminderMinutesBefore = minutesBefore)

    /**
     * The memorable date picked as day, month and maybe [year]; without a year [today]'s year holds it and years are
     * not counted. A day past the month's end becomes its last day.
     */
    fun withMemorableDate(
        day: Int,
        month: Int,
        year: Int?,
        today: LocalDate,
    ): EventForm {
        val date = clampedDate(year ?: today.year, month, day)
        return copy(
            startDate = date,
            endDate = date,
            yearKnown = year != null,
            countYears = countYears && year != null,
        )
    }

    fun withCountYears(value: Boolean) = copy(countYears = value && yearKnown)

    /** The years a memorable date counts this year (shown under «Считать годы»), when known and positive. */
    fun yearsThisYear(today: LocalDate): Int? = (today.year - startDate.year).takeIf { yearKnown && it > 0 }

    /** The event as the form describes it; [createdBy] for a new one (the writer sets it again). */
    fun toData(createdBy: String = original?.createdBy.orEmpty()): EventData {
        val base =
            EventData(
                title = title.trim(),
                createdBy = createdBy,
                startDate = startDate,
                endDate = endDate,
                allDay = allDay,
                startTime = if (allDay) null else startTime,
                endTime = if (allDay) null else endTime,
                place = place.trim().ifEmpty { null },
                notes = notes.trim().ifEmpty { null },
                participantIds = participantIds,
                tagIds = tagIds,
                recurrence = recurrence,
                repeatUntil = if (recurrence == null) null else repeatUntil,
                reminderMinutesBefore = reminderMinutesBefore,
                kind = kind,
                splitFrom = original?.splitFrom,
            )
        if (!memorable) return base
        return base.copy(
            allDay = true,
            endDate = startDate,
            startTime = null,
            endTime = null,
            place = null,
            recurrence = Validators.MEMORABLE_RECURRENCE,
            repeatUntil = null,
            reminderMinutesBefore = null,
            countYears = countYears && yearKnown,
        )
    }

    /**
     * [base] (the event as stored now) with the fields this form changed compared to [original]. The time fields go
     * together: if one changed, all of them are taken from the form. Without an original (a new event) it is [toData].
     */
    fun applyTo(base: EventData): EventData {
        val before = original ?: return toData(base.createdBy)
        val after = toData(before.createdBy)

        fun <T> pick(field: (EventData) -> T): T = if (field(after) != field(before)) field(after) else field(base)
        val timesChanged = TIME_FIELDS.any { it(after) != it(before) }
        val times = if (timesChanged) after else base
        return base.copy(
            title = pick { it.title },
            startDate = times.startDate,
            endDate = times.endDate,
            allDay = times.allDay,
            startTime = times.startTime,
            endTime = times.endTime,
            place = pick { it.place },
            notes = pick { it.notes },
            participantIds = pick { it.participantIds },
            tagIds = pick { it.tagIds },
            recurrence = pick { it.recurrence },
            repeatUntil = pick { it.repeatUntil },
            reminderMinutesBefore = pick { it.reminderMinutesBefore },
            kind = pick { it.kind },
            countYears = pick { it.countYears },
        )
    }

    /** True if saving «Все» changes the start date, all-day or the rule: changed occurrences are reset (CAL-015). */
    fun touchesSchedule(): Boolean {
        val before = original ?: return false
        val after = toData(before.createdBy)
        return after.startDate != before.startDate || after.allDay != before.allDay || after.recurrence != before.recurrence
    }

    /**
     * «Только это»: the override of one occurrence for [CalendarWriter.editOccurrence]. Fields the user changed carry
     * the new value; the others keep what [row] (the stored override, if any) has, so earlier changes of this
     * occurrence stay — null when there was none.
     */
    fun occurrenceChanges(row: EventOccurrenceData?): OccurrenceChanges {
        val before = original ?: return OccurrenceChanges()
        val after = toData(before.createdBy)
        val timesChanged = TIME_FIELDS.any { it(after) != it(before) }

        fun <T> pick(
            field: (EventData) -> T,
            stored: T?,
        ): T? = if (field(after) != field(before)) field(after) else stored
        return OccurrenceChanges(
            startDate = if (timesChanged) after.startDate else row?.startDate,
            endDate = if (timesChanged) after.endDate else row?.endDate,
            startTime = if (timesChanged) after.startTime else row?.startTime,
            endTime = if (timesChanged) after.endTime else row?.endTime,
            title = pick({ it.title }, row?.title),
            place = if (after.place != before.place) after.place.orEmpty() else row?.place,
            notes = if (after.notes != before.notes) after.notes.orEmpty() else row?.notes,
            participantIds = pick({ it.participantIds }, row?.participantIds),
        )
    }

    /** The same rules as the server (`Validators.event`), keyed by field. */
    fun validate(): Map<String, FieldErrorCode> = Validators.event(toData())

    private fun durationMinutes(): Int {
        val start = startTime ?: return MINUTES_PER_HOUR
        val end = endTime ?: return MINUTES_PER_HOUR
        val minutes = (LocalDateTime(endDate, end).toInstant(TimeZone.UTC) - LocalDateTime(startDate, start).toInstant(TimeZone.UTC))
        return minutes.inWholeMinutes.toInt().coerceAtLeast(1)
    }

    private fun withEnd(end: LocalDateTime): EventForm {
        val start = LocalDateTime(startDate, startTime ?: NINE)
        return withTimes(start, if (end <= start) plusMinutes(start, MINUTES_PER_HOUR) else end)
    }

    private fun withTimes(
        start: LocalDateTime,
        end: LocalDateTime,
    ) = copy(startDate = start.date, startTime = start.time, endDate = end.date, endTime = end.time)

    companion object {
        val EPOCH = LocalDate(1970, 1, 1)
        private val NINE = LocalTime(9, 0)
        private val TEN = LocalTime(10, 0)
        private const val MINUTES_PER_HOUR = 60
        private const val LAST_HOUR = 23

        private val TIME_FIELDS: List<(EventData) -> Any?> =
            listOf({ it.startDate }, { it.endDate }, { it.allDay }, { it.startTime }, { it.endTime })

        /** Fields shown next to their inputs; other errors appear under the form. */
        val FIELDS = setOf("title", "endDate", "repeatUntil")

        /** A new event (plan M3-06): on [date] at [hour] for one hour; an end past midnight moves to the next day. */
        fun new(
            date: LocalDate,
            hour: Int,
            memorable: Boolean = false,
        ): EventForm {
            val start = LocalDateTime(date, LocalTime(hour.coerceIn(0, LAST_HOUR), 0))
            val end = plusMinutes(start, MINUTES_PER_HOUR)
            return EventForm(
                kind = if (memorable) EventKind.MEMORABLE_DATE else EventKind.EVENT,
                startDate = start.date,
                startTime = start.time,
                endDate = end.date,
                endTime = end.time,
            )
        }

        /** The hour a new event starts at when none is given: the next full hour after [now], at most 23. */
        fun nextHour(now: LocalTime): Int = (now.hour + 1).coerceAtMost(LAST_HOUR)

        /** The form of a stored event or memorable date («Все»). */
        fun of(
            data: EventData,
            today: LocalDate,
        ): EventForm {
            val memorable = data.kind == EventKind.MEMORABLE_DATE
            return EventForm(
                kind = data.kind,
                title = data.title,
                allDay = data.allDay || memorable,
                startDate = data.startDate,
                startTime = data.startTime,
                endDate = data.endDate,
                endTime = data.endTime,
                recurrence = data.recurrence,
                repeatUntil = data.repeatUntil,
                participantIds = data.participantIds,
                place = data.place.orEmpty(),
                notes = data.notes.orEmpty(),
                tagIds = data.tagIds,
                reminderMinutesBefore = data.reminderMinutesBefore,
                countYears = data.countYears,
                // An unknown year is stored as the year it was entered; a counted or earlier year is known.
                yearKnown = !memorable || data.countYears || data.startDate.year < today.year,
                original = data,
            )
        }

        /** «Только это»: the form of the effective occurrence [occ] of [series] (no repeat, reminder or tags). */
        fun ofOccurrence(
            series: EventData,
            occ: EventOccurrence,
            today: LocalDate,
        ): EventForm =
            of(
                series.copy(
                    startDate = occ.startDate,
                    endDate = occ.endDate,
                    startTime = occ.startTime,
                    endTime = occ.endTime,
                    title = occ.title,
                    place = occ.place,
                    notes = occ.notes,
                    participantIds = occ.participantIds,
                    recurrence = null,
                    repeatUntil = null,
                ),
                today,
            )

        /** «Это и следующие»: [series] moved to start on its occurrence [from], with the series' rule and end. */
        fun shifted(
            series: EventData,
            from: LocalDate,
        ): EventData = series.copy(startDate = from, endDate = from.plus(EventSchedule.spanDays(series), DateTimeUnit.DAY))

        /** Field errors shown on the inputs: «Слишком длинное событие» and «Раньше начала» for the calendar's own cases. */
        fun fieldErrors(errors: Map<String, FieldErrorCode>): Map<String, FieldError> =
            errors
                .filterKeys { it in FIELDS }
                .mapNotNull { (field, code) ->
                    val error =
                        when {
                            field == "endDate" && code == FieldErrorCode.OUT_OF_RANGE -> FieldError(Res.string.cal_error_too_long)
                            field == "repeatUntil" && code == FieldErrorCode.OUT_OF_RANGE -> FieldError(Res.string.cal_error_before_start)
                            else -> FieldError.of(code)
                        }
                    error?.let { field to it }
                }.toMap()

        /** The error of a refused end date. */
        val TOO_LONG = FieldError(Res.string.cal_error_too_long)

        private fun tooLong(
            start: LocalDate,
            end: LocalDate,
        ) = start.daysUntil(end) > EventSchedule.MAX_SPAN_DAYS

        private fun plusMinutes(
            at: LocalDateTime,
            minutes: Int,
        ): LocalDateTime = (at.toInstant(TimeZone.UTC) + minutes.minutes).toLocalDateTime(TimeZone.UTC)

        private fun clampedDate(
            year: Int,
            month: Int,
            day: Int,
        ): LocalDate {
            val first = LocalDate(year, month, 1)
            val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
            return LocalDate(year, month, day.coerceAtMost(last))
        }
    }
}
