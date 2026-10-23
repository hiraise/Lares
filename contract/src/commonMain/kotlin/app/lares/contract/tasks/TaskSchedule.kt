package app.lares.contract.tasks

import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The current occurrence of a task: [occurrence] is the due date of a one-off (may be null) or the open date of a
 * routine. [done] only for one-offs; [doneBy] is then the earliest non-skipped completion.
 */
data class TaskState(
    val occurrence: LocalDate?,
    val done: Boolean,
    val doneBy: TaskCompletionData?,
)

/** List sections of the task views (TSK-020). */
enum class TaskGroup { OVERDUE, TODAY, UPCOMING, NO_DATE, DONE }

/**
 * Derived state of tasks and routines (ADR-0017, architecture/tasks.md § Schedule logic). Pure: callers pass the clock
 * and the household time zone. Completions passed in are the task's non-deleted ones.
 */
object TaskSchedule {
    /** The moment of a date-only task: reminders on the day and overdue notices the next morning (Q-016). */
    val DAY_START: LocalTime = LocalTime(9, 0)

    fun state(
        task: TaskData,
        completions: List<TaskCompletionData>,
        today: LocalDate,
        zone: TimeZone,
    ): TaskState {
        val rule = task.recurrence
        val anchor = task.dueDate
        if (rule == null) {
            val doneBy = completions.filter { !it.skipped }.minWithOrNull(compareBy({ it.completedAt }, { it.completedBy }))
            return TaskState(anchor, done = doneBy != null, doneBy = doneBy)
        }
        // A routine without a start date is rejected by the validators; never done, no date.
        if (anchor == null) return TaskState(null, done = false, doneBy = null)
        val occurrence =
            when (rule.mode) {
                RecurrenceMode.FIXED -> {
                    val latest = completions.mapNotNull { it.occurrence }.maxOrNull()
                    val from = latest?.plus(1, DateTimeUnit.DAY) ?: anchor
                    val first = Recurrences.firstOnOrAfter(rule, anchor, from)
                    if (first <= today) Recurrences.lastOnOrBefore(rule, anchor, today) ?: first else first
                }

                RecurrenceMode.AFTER_COMPLETION -> {
                    val last = completions.maxByOrNull { it.completedAt }
                    if (last == null) {
                        anchor
                    } else {
                        val completedOn = last.completedAt.toLocalDateTime(zone).date
                        maxOf(anchor, Recurrences.plusUnits(completedOn, rule.unit, rule.interval))
                    }
                }
            }
        return TaskState(occurrence, done = false, doneBy = null)
    }

    /** The due moment of [occurrence] for a task with a time; null for date-only tasks. */
    fun dueMoment(
        task: TaskData,
        occurrence: LocalDate,
        zone: TimeZone,
    ): Instant? = task.dueTime?.let { LocalDateTime(occurrence, it).toInstant(zone) }

    fun isOverdue(
        task: TaskData,
        state: TaskState,
        now: Instant,
        zone: TimeZone,
    ): Boolean {
        val occurrence = state.occurrence
        if (state.done || occurrence == null) return false
        val due = dueMoment(task, occurrence, zone)
        return if (due != null) now >= due else now.toLocalDateTime(zone).date > occurrence
    }

    fun group(
        task: TaskData,
        state: TaskState,
        now: Instant,
        zone: TimeZone,
    ): TaskGroup {
        val occurrence = state.occurrence
        return when {
            state.done -> TaskGroup.DONE
            occurrence == null -> TaskGroup.NO_DATE
            isOverdue(task, state, now, zone) -> TaskGroup.OVERDUE
            occurrence <= now.toLocalDateTime(zone).date -> TaskGroup.TODAY
            else -> TaskGroup.UPCOMING
        }
    }

    /** When the reminder of [occurrence] fires (TSK-030); null without a reminder. */
    fun reminderAt(
        task: TaskData,
        occurrence: LocalDate,
        zone: TimeZone,
    ): Instant? {
        val before = task.reminderMinutesBefore ?: return null
        val base = dueMoment(task, occurrence, zone) ?: LocalDateTime(occurrence, DAY_START).toInstant(zone)
        return base - before.minutes
    }

    /** When the creator is told that [occurrence] is overdue (TSK-033): the due moment, or the next morning. */
    fun overdueNoticeAt(
        task: TaskData,
        occurrence: LocalDate,
        zone: TimeZone,
    ): Instant =
        dueMoment(task, occurrence, zone)
            ?: LocalDateTime(occurrence.plus(1, DateTimeUnit.DAY), DAY_START).toInstant(zone)

    /** Routine checklists start unticked for every occurrence (TSK-004). */
    fun isChecklistItemDone(
        task: TaskData,
        item: ChecklistItemData,
        state: TaskState,
    ): Boolean = item.done && (task.recurrence == null || item.doneOccurrence == state.occurrence)

    /**
     * The next moment the server's overdue job must look at the task (architecture/tasks.md § Overdue job), given the
     * occurrence it last notified about; null when nothing can become overdue any more.
     */
    fun nextOverdueCheck(
        task: TaskData,
        completions: List<TaskCompletionData>,
        notifiedOccurrence: LocalDate?,
        now: Instant,
        zone: TimeZone,
    ): Instant? {
        val state = state(task, completions, now.toLocalDateTime(zone).date, zone)
        val current = state.occurrence
        if (state.done || current == null) return null
        if (notifiedOccurrence != current) return overdueNoticeAt(task, current, zone)
        val rule = task.recurrence
        val anchor = task.dueDate
        if (rule?.mode != RecurrenceMode.FIXED || anchor == null) return null
        val next = Recurrences.firstOnOrAfter(rule, anchor, current.plus(1, DateTimeUnit.DAY))
        return overdueNoticeAt(task, next, zone)
    }
}
