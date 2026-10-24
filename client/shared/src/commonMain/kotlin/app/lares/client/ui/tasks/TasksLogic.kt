package app.lares.client.ui.tasks

import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskView
import app.lares.client.resources.Res
import app.lares.client.resources.date_day_month
import app.lares.client.resources.date_day_month_short
import app.lares.client.resources.date_day_month_year
import app.lares.client.resources.date_with_weekday
import app.lares.client.resources.day_today
import app.lares.client.resources.day_tomorrow
import app.lares.client.resources.day_yesterday
import app.lares.client.resources.list_and
import app.lares.client.resources.months
import app.lares.client.resources.months_short
import app.lares.client.resources.task_done_by_at
import app.lares.client.resources.task_history_done
import app.lares.client.resources.task_history_skipped
import app.lares.client.resources.task_history_when
import app.lares.client.resources.task_meta_until
import app.lares.client.resources.task_reminder_at_due
import app.lares.client.resources.task_reminder_days
import app.lares.client.resources.task_reminder_days_at
import app.lares.client.resources.task_reminder_hours
import app.lares.client.resources.task_reminder_minutes
import app.lares.client.resources.task_reminder_on_day
import app.lares.client.resources.task_repeat_after_day
import app.lares.client.resources.task_repeat_after_days
import app.lares.client.resources.task_repeat_after_month
import app.lares.client.resources.task_repeat_after_months
import app.lares.client.resources.task_repeat_after_week
import app.lares.client.resources.task_repeat_after_weeks
import app.lares.client.resources.task_repeat_after_year
import app.lares.client.resources.task_repeat_after_years
import app.lares.client.resources.task_repeat_daily
import app.lares.client.resources.task_repeat_days
import app.lares.client.resources.task_repeat_every_weekday
import app.lares.client.resources.task_repeat_monthly
import app.lares.client.resources.task_repeat_months
import app.lares.client.resources.task_repeat_weekly_on
import app.lares.client.resources.task_repeat_weeks_on
import app.lares.client.resources.task_repeat_yearly
import app.lares.client.resources.task_repeat_years
import app.lares.client.resources.task_status_done
import app.lares.client.resources.task_status_overdue
import app.lares.client.resources.tasks_overdue_since
import app.lares.client.resources.time_on_date
import app.lares.client.resources.weekdays
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.Recurrences
import app.lares.contract.tasks.TaskState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** How the meta line of a task row is drawn. */
enum class MetaKind { PLAIN, OVERDUE, ROUTINE }

/** The meta line under a task title; [text] null = no line (tags and checklist progress may still show). */
data class TaskMeta(
    val text: UiText?,
    val kind: MetaKind = MetaKind.PLAIN,
)

/**
 * Pure formatting of the tasks UI (plan M1-05): relative days, repeat rule summaries, reminder labels, meta lines.
 * Returns [UiText] so the texts follow the app language; nothing here reads the clock or the locale.
 */
object TasksLogic {
    /** "9:05", "20:00" (24-hour, as in the mockups). */
    fun time(time: LocalTime): String = "${time.hour}:${time.minute.toString().padStart(2, '0')}"

    fun time(
        instant: Instant,
        zone: TimeZone,
    ): String = time(instant.toLocalDateTime(zone).time)

    /** "24 October" / "24 октября". */
    fun dayMonth(date: LocalDate): UiText = uiText(Res.string.date_day_month, raw(date.day.toString()), month(date))

    /** "Oct 21" / "21 окт.". */
    fun shortDate(date: LocalDate): UiText =
        uiText(Res.string.date_day_month_short, raw(date.day.toString()), UiText.Item(Res.array.months_short, date.month.ordinal))

    /**
     * Today / Tomorrow / Yesterday; "Saturday, October 31" within the next 6 days; otherwise the date, with the year
     * when it is not this year.
     */
    fun day(
        date: LocalDate,
        today: LocalDate,
    ): UiText {
        val days = today.daysUntil(date)
        return when {
            days == 0 -> {
                uiText(Res.string.day_today)
            }

            days == 1 -> {
                uiText(Res.string.day_tomorrow)
            }

            days == -1 -> {
                uiText(Res.string.day_yesterday)
            }

            days in 2..6 -> {
                uiText(Res.string.date_with_weekday, weekday(date.dayOfWeek), dayMonth(date))
            }

            date.year != today.year -> {
                uiText(
                    Res.string.date_day_month_year,
                    raw(date.day.toString()),
                    month(date),
                    raw(date.year.toString()),
                )
            }

            else -> {
                dayMonth(date)
            }
        }
    }

    /** "Today, 17:30", "Tomorrow", "Saturday, October 31". */
    fun due(
        date: LocalDate,
        time: LocalTime?,
        today: LocalDate,
    ): UiText = if (time == null) day(date, today) else uiText(Res.string.time_on_date, day(date, today), raw(time(time)))

    /** Overdue since today or yesterday: "Yesterday, 21:00"; earlier: "Overdue since Oct 21". */
    fun overdue(
        date: LocalDate,
        time: LocalTime?,
        today: LocalDate,
    ): UiText = if (date.daysUntil(today) <= 1) due(date, time, today) else uiText(Res.string.tasks_overdue_since, shortDate(date))

    /** Summary of a repeat rule; [anchor] is the start date (weekday of a weekly rule without days, day of month). */
    fun rule(
        rule: Recurrence,
        anchor: LocalDate?,
    ): UiText {
        val n = rule.interval.coerceAtLeast(1)
        if (rule.mode == RecurrenceMode.AFTER_COMPLETION) return afterCompletion(rule.unit, n)
        return when (rule.unit) {
            RecurrenceUnit.DAY -> {
                if (n == 1) uiText(Res.string.task_repeat_daily) else plural(Res.plurals.task_repeat_days, n)
            }

            RecurrenceUnit.WEEK -> {
                val days = rule.weekdays.ifEmpty { setOfNotNull(anchor?.dayOfWeek) }.sortedBy { it.ordinal }
                when {
                    n == 1 && days.size == DayOfWeek.entries.size -> uiText(Res.string.task_repeat_daily)
                    n == 1 && days.size == 1 -> UiText.Item(Res.array.task_repeat_every_weekday, days.single().ordinal)
                    n == 1 -> uiText(Res.string.task_repeat_weekly_on, weekdayList(days))
                    else -> plural(Res.plurals.task_repeat_weeks_on, n, weekdayList(days))
                }
            }

            RecurrenceUnit.MONTH -> {
                val day = raw((anchor?.day ?: 1).toString())
                if (n == 1) uiText(Res.string.task_repeat_monthly, day) else plural(Res.plurals.task_repeat_months, n, day)
            }

            RecurrenceUnit.YEAR -> {
                val date = anchor?.let(::dayMonth) ?: raw("")
                if (n == 1) uiText(Res.string.task_repeat_yearly, date) else plural(Res.plurals.task_repeat_years, n, date)
            }
        }
    }

    /** The rule summary with the time: "Every day · by 20:00". */
    fun ruleWithTime(task: TaskData): UiText? {
        val rule = task.recurrence ?: return null
        val summary = rule(rule, task.dueDate)
        val time = task.dueTime ?: return summary
        return UiText.Concat(listOf(summary, uiText(Res.string.task_meta_until, raw(time(time)))), " · ")
    }

    /** The occurrence after the current one of a fixed routine; null for one-offs and after-completion rules. */
    fun nextOccurrence(
        task: TaskData,
        state: TaskState,
    ): LocalDate? {
        val rule = task.recurrence ?: return null
        val anchor = task.dueDate ?: return null
        val current = state.occurrence ?: return null
        if (rule.mode != RecurrenceMode.FIXED) return null
        return Recurrences.firstOnOrAfter(rule, anchor, current.plus(1, DateTimeUnit.DAY))
    }

    /** "15 minutes before", "1 day before"; date-only tasks remind at 9:00 ("1 day before at 9:00", 0 = on the day). */
    fun reminder(
        minutesBefore: Int,
        hasTime: Boolean,
    ): UiText =
        when {
            minutesBefore <= 0 -> {
                uiText(if (hasTime) Res.string.task_reminder_at_due else Res.string.task_reminder_on_day)
            }

            minutesBefore % MINUTES_PER_DAY == 0 -> {
                plural(if (hasTime) Res.plurals.task_reminder_days else Res.plurals.task_reminder_days_at, minutesBefore / MINUTES_PER_DAY)
            }

            minutesBefore % MINUTES_PER_HOUR == 0 -> {
                plural(Res.plurals.task_reminder_hours, minutesBefore / MINUTES_PER_HOUR)
            }

            else -> {
                plural(Res.plurals.task_reminder_minutes, minutesBefore)
            }
        }

    /**
     * The meta line of a row: overdue date (error color) → routine summary → due date → first line of the notes.
     * A "done today" row shows who completed it and when: "Anya · at 9:12". [names] maps member ids to names.
     */
    fun meta(
        view: TaskView,
        today: LocalDate,
        zone: TimeZone,
        names: Map<String, String>,
    ): TaskMeta {
        val doneToday = view.completedToday
        if (view.isDoneTodayRow && doneToday != null) {
            val name = names[doneToday.data.completedBy].orEmpty()
            return TaskMeta(uiText(Res.string.task_done_by_at, raw(name), raw(time(doneToday.data.completedAt, zone))))
        }
        val occurrence = view.state.occurrence
        val notes =
            view.data.notes
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
        return when {
            view.overdue && occurrence != null -> TaskMeta(overdue(occurrence, view.data.dueTime, today), MetaKind.OVERDUE)
            view.data.recurrence != null -> TaskMeta(ruleWithTime(view.data), MetaKind.ROUTINE)
            occurrence != null -> TaskMeta(due(occurrence, view.data.dueTime, today))
            notes != null -> TaskMeta(raw(notes))
            else -> TaskMeta(null)
        }
    }

    /** Status line of the task page: overdue, due date, or "Done: Anya, Oct 23, 9:12"; null without a date. */
    fun status(
        view: TaskView,
        today: LocalDate,
        zone: TimeZone,
        names: Map<String, String>,
    ): UiText? {
        val doneBy = view.state.doneBy
        val occurrence = view.state.occurrence
        return when {
            view.state.done && doneBy != null -> {
                val at = doneBy.completedAt.toLocalDateTime(zone)
                val whenText = uiText(Res.string.time_on_date, shortDate(at.date), raw(time(at.time)))
                uiText(Res.string.task_status_done, raw(names[doneBy.completedBy].orEmpty()), whenText)
            }

            view.overdue && occurrence != null -> {
                uiText(Res.string.task_status_overdue, due(occurrence, view.data.dueTime, today))
            }

            occurrence != null -> {
                due(occurrence, view.data.dueTime, today)
            }

            else -> {
                null
            }
        }
    }

    /** A history entry: "Anya · Tue, Oct 21, 19:40" or "Skipped · Boris · …". */
    fun history(
        completion: TaskCompletion,
        zone: TimeZone,
        names: Map<String, String>,
    ): UiText {
        val at = completion.data.completedAt.toLocalDateTime(zone)
        val whenText =
            uiText(
                Res.string.task_history_when,
                UiText.Item(Res.array.weekdays_short, at.dayOfWeek.ordinal),
                shortDate(at.date),
                raw(time(at.time)),
            )
        val name = raw(names[completion.data.completedBy].orEmpty())
        return uiText(if (completion.data.skipped) Res.string.task_history_skipped else Res.string.task_history_done, name, whenText)
    }

    private fun afterCompletion(
        unit: RecurrenceUnit,
        n: Int,
    ): UiText =
        when (unit) {
            RecurrenceUnit.DAY -> {
                if (n == 1) uiText(Res.string.task_repeat_after_day) else plural(Res.plurals.task_repeat_after_days, n)
            }

            RecurrenceUnit.WEEK -> {
                if (n == 1) uiText(Res.string.task_repeat_after_week) else plural(Res.plurals.task_repeat_after_weeks, n)
            }

            RecurrenceUnit.MONTH -> {
                if (n == 1) uiText(Res.string.task_repeat_after_month) else plural(Res.plurals.task_repeat_after_months, n)
            }

            RecurrenceUnit.YEAR -> {
                if (n == 1) uiText(Res.string.task_repeat_after_year) else plural(Res.plurals.task_repeat_after_years, n)
            }
        }

    /** "Mon", "Mon and Thu", "Mon, Wed and Fri". */
    private fun weekdayList(days: List<DayOfWeek>): UiText {
        val names = days.map { UiText.Item(Res.array.weekdays_short, it.ordinal) }
        if (names.size <= 1) return names.firstOrNull() ?: raw("")
        return uiText(Res.string.list_and, UiText.Concat(names.dropLast(1), ", "), names.last())
    }

    private fun weekday(day: DayOfWeek): UiText = UiText.Item(Res.array.weekdays, day.ordinal)

    private fun month(date: LocalDate): UiText = UiText.Item(Res.array.months, date.month.ordinal)

    private fun plural(
        res: org.jetbrains.compose.resources.PluralStringResource,
        n: Int,
        vararg more: UiText,
    ): UiText = UiText.Plural(res, n, listOf(raw(n.toString())) + more)

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * 60
}
