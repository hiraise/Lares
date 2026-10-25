package app.lares.client.data.calendar

import app.lares.client.data.MemberView
import app.lares.client.data.Person
import app.lares.client.data.Tag
import app.lares.client.data.tasks.TaskView
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.Recurrence
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

// What the calendar repository exposes (architecture/calendar.md § Models and views, plan M3-04).

/**
 * Everything the calendar views are computed from. [myReminders] are the viewer's own reminder rows; [tasks] are the
 * task views, empty without `tasks.view`; [viewer] is null when nobody is logged in.
 */
data class CalendarInputs(
    val events: List<Pair<String, EventData>>,
    val occurrenceRows: List<EventOccurrenceData>,
    val myReminders: List<EventReminderData>,
    val persons: List<Person>,
    val members: List<MemberView>,
    val tags: List<Tag>,
    val tasks: List<TaskView>,
    val zone: TimeZone,
    val viewer: Viewer?,
    val now: Instant,
)

/** One row of a day list. */
sealed interface CalendarItem {
    /**
     * An occurrence of an event or memorable date on one day. [participants] and [tags] drop unknown ids; [years] is the
     * count of a memorable date; [continuesBefore] / [continuesAfter] mark a day in the middle of a multi-day occurrence.
     */
    data class Event(
        val occ: EventOccurrence,
        val kind: EventKind,
        val participants: List<Person>,
        val tags: List<Tag>,
        val repeating: Boolean,
        val years: Int?,
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    ) : CalendarItem

    /** [age] is null when the birth year is unknown. */
    data class Birthday(
        val person: Person,
        val date: LocalDate,
        val age: Int?,
    ) : CalendarItem

    /** A task on its current occurrence date, or on today when [overdue] or done today. */
    data class Task(
        val view: TaskView,
        val overdue: Boolean,
    ) : CalendarItem
}

/** Person filter: events they take part in (or of the whole family), their birthday, their tasks; tag filter: no birthdays. */
data class CalendarFilter(
    val personId: String? = null,
    val tagId: String? = null,
    val showTasks: Boolean = true,
)

data class DayView(
    val date: LocalDate,
    val items: List<CalendarItem>,
)

/** [dots]: distinct participant colors, at most 3; null = the whole family (drawn `primary`). */
data class MonthCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val today: Boolean,
    val dots: List<PaletteColor?>,
    val more: Boolean,
)

/** A timed event on one day of the week grid; minutes from 00:00, at least 30 long; [column] of [columns] in its cluster. */
data class TimedBlock(
    val item: CalendarItem.Event,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

data class WeekDay(
    val date: LocalDate,
    val allDay: List<CalendarItem>,
    val blocks: List<TimedBlock>,
)

/** An item with the day it is shown on. */
data class DatedItem(
    val date: LocalDate,
    val item: CalendarItem,
)

/** Today «Скоро» (CAL-019): at most [CalendarViews.UPCOMING_MAX] rows, how many more there are, the household's today. */
data class Upcoming(
    val items: List<DatedItem>,
    val more: Int,
    val today: LocalDate,
)

data class MemorableDateView(
    val eventId: String,
    val title: String,
    val next: LocalDate,
    val years: Int?,
)

/** The person page (CAL-005): their next birthday and memorable dates, nearest first; the household's today. */
data class PersonDates(
    val birthday: CalendarItem.Birthday?,
    val memorable: List<MemorableDateView>,
    val today: LocalDate,
)

/**
 * The event page (CAL-014). [occ] is the given occurrence, else the next one (null if none is left).
 * [myReminder] is what fires for the viewer; [ownReminderSet] is true when the viewer's own row differs from what the
 * event gives them («Как в событии» otherwise); [eventReminder] is what the event alone gives them (null: the event has no
 * reminder for them). [changedCount] counts changed or cancelled occurrences.
 */
data class EventDetail(
    val id: String,
    val event: EventData,
    val occ: EventOccurrence?,
    val participants: List<Person>,
    val tags: List<Tag>,
    val creator: MemberView?,
    val rule: Recurrence?,
    val years: Int?,
    val myReminder: Int?,
    val ownReminderSet: Boolean,
    val eventReminder: Int? = null,
    val canEdit: Boolean,
    val changedCount: Int,
)
