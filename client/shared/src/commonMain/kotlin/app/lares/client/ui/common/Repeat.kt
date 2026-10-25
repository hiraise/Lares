package app.lares.client.ui.common

import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.LocalDate

/** The single-choice rows of the repeat sheet; [CUSTOM] is any other rule. Shared by the task and event editors. */
enum class RepeatPreset { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

/** Weekdays matter only for fixed weekly rules. */
fun Recurrence.isWeekly(): Boolean = mode == RecurrenceMode.FIXED && unit == RecurrenceUnit.WEEK

/** Repeat rule helpers of the editors: presets, and weekdays that follow the start date. */
object Repeats {
    /** The preset [rule] matches when the schedule starts on [start] (null start: weekly is custom). */
    fun preset(
        rule: Recurrence?,
        start: LocalDate?,
    ): RepeatPreset {
        if (rule == null) return RepeatPreset.NONE
        if (rule.mode != RecurrenceMode.FIXED || rule.interval != 1) return RepeatPreset.CUSTOM
        return when (rule.unit) {
            RecurrenceUnit.DAY -> RepeatPreset.DAILY
            RecurrenceUnit.WEEK -> if (rule.weekdays == setOfNotNull(start?.dayOfWeek)) RepeatPreset.WEEKLY else RepeatPreset.CUSTOM
            RecurrenceUnit.MONTH -> RepeatPreset.MONTHLY
            RecurrenceUnit.YEAR -> RepeatPreset.YEARLY
        }
    }

    /** The rule of [preset] starting on [date]; null for none and custom. */
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

    /** A weekly rule without weekdays gets [date]'s weekday; other rules drop their weekdays. */
    fun fit(
        rule: Recurrence,
        date: LocalDate,
    ): Recurrence =
        when {
            rule.isWeekly() && rule.weekdays.isEmpty() -> rule.copy(weekdays = setOf(date.dayOfWeek))
            !rule.isWeekly() && rule.weekdays.isNotEmpty() -> rule.copy(weekdays = emptySet())
            else -> rule
        }

    /** A weekly rule on the old start's weekday only follows the start to [new]; other rules stay. */
    fun follow(
        rule: Recurrence?,
        old: LocalDate?,
        new: LocalDate,
    ): Recurrence? =
        if (rule != null && old != null && rule.isWeekly() && rule.weekdays == setOf(old.dayOfWeek)) {
            rule.copy(weekdays = setOf(new.dayOfWeek))
        } else {
            rule
        }
}
