package app.lares.client.ui.tasks

import app.lares.client.ui.common.FieldError
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import app.lares.contract.validation.Validators
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The single-choice rows of the repeat sheet; [CUSTOM] is any other rule. */
enum class RepeatPreset { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

/**
 * The editable fields of the task editor (plan M1-06), pure: no clock, no database. [original] is the stored task
 * when editing; only fields that differ from it are written ([applyTo]), so an unchanged routine start date is never
 * rewritten and changes made on another phone meanwhile are kept.
 */
data class TaskForm(
    val title: String = "",
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val recurrence: Recurrence? = null,
    /** Empty = "anyone" (TSK-002). */
    val assigneeIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val reminderMinutesBefore: Int? = null,
    val notifyCreatorOnDone: Boolean = false,
    /** Checklist items typed on create; blank ones are dropped. */
    val checklist: List<String> = emptyList(),
    val original: TaskData? = null,
) {
    val isRoutine: Boolean get() = recurrence != null

    /** An existing routine cannot become a one-off (ADR-0017): "Do not repeat" is disabled. */
    val canStopRepeat: Boolean get() = original?.recurrence == null

    fun withTitle(value: String) = copy(title = value.take(Validators.TASK_TITLE_MAX))

    fun withNotes(value: String) = copy(notes = value.take(Validators.NOTES_MAX))

    /**
     * A new date. Clearing it also clears the time and the reminder; a routine always keeps a date. A weekly preset
     * follows the date's weekday.
     */
    fun withDate(date: LocalDate?): TaskForm {
        if (date == null) {
            return if (isRoutine) this else copy(dueDate = null, dueTime = null, reminderMinutesBefore = null)
        }
        val rule = recurrence
        val old = dueDate
        val followed =
            if (rule != null && old != null && rule.isWeekly() && rule.weekdays == setOf(old.dayOfWeek)) {
                rule.copy(weekdays = setOf(date.dayOfWeek))
            } else {
                rule
            }
        return copy(dueDate = date, recurrence = followed)
    }

    /** A time needs a date; switching between "with time" and "without time" resets a reminder that no longer fits. */
    fun withTime(time: LocalTime?): TaskForm {
        if (time != null && dueDate == null) return this
        val reminder = reminderMinutesBefore
        val fits = reminder == null || reminder in reminderOptions(time != null)
        return copy(dueTime = time, reminderMinutesBefore = if (fits) reminder else 0)
    }

    /** A repeat rule; choosing one without a date starts it [today]. Null (stop repeating) only for a new routine. */
    fun withRecurrence(
        rule: Recurrence?,
        today: LocalDate,
    ): TaskForm {
        if (rule == null) return if (canStopRepeat) copy(recurrence = null) else this
        val date = dueDate ?: today
        val fixedRule =
            when {
                rule.isWeekly() && rule.weekdays.isEmpty() -> rule.copy(weekdays = setOf(date.dayOfWeek))
                !rule.isWeekly() && rule.weekdays.isNotEmpty() -> rule.copy(weekdays = emptySet())
                else -> rule
            }
        return copy(dueDate = date, recurrence = fixedRule)
    }

    fun withPreset(
        preset: RepeatPreset,
        today: LocalDate,
    ): TaskForm =
        when (preset) {
            RepeatPreset.NONE -> withRecurrence(null, today)
            RepeatPreset.CUSTOM -> this
            else -> withRecurrence(presetRule(preset, dueDate ?: today), today)
        }

    /** "Anyone" clears the assignees; a member is toggled. */
    fun withAnyone() = copy(assigneeIds = emptySet())

    fun toggleAssignee(memberId: String) =
        copy(assigneeIds = if (memberId in assigneeIds) assigneeIds - memberId else assigneeIds + memberId)

    fun toggleTag(tagId: String) = copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)

    fun withReminder(minutesBefore: Int?) = copy(reminderMinutesBefore = if (dueDate == null) null else minutesBefore)

    fun preset(): RepeatPreset {
        val rule = recurrence ?: return RepeatPreset.NONE
        if (rule.mode != RecurrenceMode.FIXED || rule.interval != 1) return RepeatPreset.CUSTOM
        return when (rule.unit) {
            RecurrenceUnit.DAY -> RepeatPreset.DAILY
            RecurrenceUnit.WEEK -> if (rule.weekdays == setOfNotNull(dueDate?.dayOfWeek)) RepeatPreset.WEEKLY else RepeatPreset.CUSTOM
            RecurrenceUnit.MONTH -> RepeatPreset.MONTHLY
            RecurrenceUnit.YEAR -> RepeatPreset.YEARLY
        }
    }

    /** The task as the form describes it; [createdBy] for a new task (the writer sets it again). */
    fun toData(createdBy: String = original?.createdBy.orEmpty()): TaskData =
        TaskData(
            title = title.trim(),
            createdBy = createdBy,
            notes = notes.trim().ifEmpty { null },
            dueDate = dueDate,
            dueTime = dueTime,
            assigneeIds = assigneeIds,
            tagIds = tagIds,
            recurrence = recurrence,
            reminderMinutesBefore = if (dueDate == null) null else reminderMinutesBefore,
            notifyCreatorOnDone = notifyCreatorOnDone,
        )

    /**
     * [base] (the task as stored now) with the fields this form changed compared to [original]. Without an original
     * (a new task) it is [toData].
     */
    fun applyTo(base: TaskData): TaskData {
        val before = original ?: return toData(base.createdBy)
        val after = toData(before.createdBy)

        fun <T> pick(field: (TaskData) -> T): T = if (field(after) != field(before)) field(after) else field(base)
        return base.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            dueDate = pick { it.dueDate },
            dueTime = pick { it.dueTime },
            assigneeIds = pick { it.assigneeIds },
            tagIds = pick { it.tagIds },
            recurrence = pick { it.recurrence },
            reminderMinutesBefore = pick { it.reminderMinutesBefore },
            notifyCreatorOnDone = pick { it.notifyCreatorOnDone },
        )
    }

    /** Checklist items to create: trimmed, blank ones dropped. */
    fun checklistItems(): List<String> = checklist.map { it.trim().take(Validators.CHECKLIST_TEXT_MAX) }.filter { it.isNotEmpty() }

    /** The same rules as the server (`Validators.task`), keyed by field. */
    fun validate(): Map<String, FieldErrorCode> = Validators.task(toData())

    companion object {
        /** Fields shown next to their inputs; other errors appear under the form. */
        val FIELDS = setOf("title", "dueDate")

        /** Reminder choices in minutes before (plan M1-06): with a time, or at 9:00 for date-only tasks. */
        val REMINDERS_WITH_TIME = listOf(0, 5, 15, 30, 60, 1440)
        val REMINDERS_WITHOUT_TIME = listOf(0, 1440, 2880)

        fun reminderOptions(hasTime: Boolean) = if (hasTime) REMINDERS_WITH_TIME else REMINDERS_WITHOUT_TIME

        /** The form of a stored task. */
        fun of(data: TaskData) =
            TaskForm(
                title = data.title,
                notes = data.notes.orEmpty(),
                dueDate = data.dueDate,
                dueTime = data.dueTime,
                recurrence = data.recurrence,
                assigneeIds = data.assigneeIds,
                tagIds = data.tagIds,
                reminderMinutesBefore = data.reminderMinutesBefore,
                notifyCreatorOnDone = data.notifyCreatorOnDone,
                original = data,
            )

        fun presetRule(
            preset: RepeatPreset,
            date: LocalDate,
        ): Recurrence? =
            when (preset) {
                RepeatPreset.NONE, RepeatPreset.CUSTOM -> null
                RepeatPreset.DAILY -> Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY)
                RepeatPreset.WEEKLY -> Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(date.dayOfWeek))
                RepeatPreset.MONTHLY -> Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.MONTH)
                RepeatPreset.YEARLY -> Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR)
            }

        /** Field errors shown on the inputs. */
        fun fieldErrors(errors: Map<String, FieldErrorCode>): Map<String, FieldError> =
            errors.filterKeys { it in FIELDS }.mapNotNull { (field, code) -> FieldError.of(code)?.let { field to it } }.toMap()
    }
}

/** Weekdays matter only for fixed weekly rules. */
fun Recurrence.isWeekly(): Boolean = mode == RecurrenceMode.FIXED && unit == RecurrenceUnit.WEEK
