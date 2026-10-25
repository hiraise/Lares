package app.lares.contract.calendar

import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Birthday and memorable-date arithmetic (CAL-004, CAL-005, CAL-016). */
object Birthdays {
    /** The date of a yearly day in [year]; 29 February falls on 28 February in non-leap years (CAL-004). */
    fun dateIn(
        year: Int,
        month: Int,
        day: Int,
    ): LocalDate {
        val first = LocalDate(year, month, 1)
        val length = first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
        return LocalDate(year, month, day.coerceIn(1, length))
    }

    /** The first date of the yearly day on or after [from]. */
    fun onOrAfter(
        month: Int,
        day: Int,
        from: LocalDate,
    ): LocalDate {
        val date = dateIn(from.year, month, day)
        return if (date >= from) date else dateIn(from.year + 1, month, day)
    }

    /** The age on the birthday [on]; null if the year is unknown or the result is below 1. */
    fun age(
        birthYear: Int?,
        on: LocalDate,
    ): Int? = birthYear?.let { on.year - it }?.takeIf { it >= 1 }

    /** Years since a memorable date began, on its occurrence [occ]; null unless counted and at least 1. */
    fun years(
        event: EventData,
        occ: LocalDate,
    ): Int? {
        if (event.kind != EventKind.MEMORABLE_DATE || !event.countYears) return null
        return (occ.year - event.startDate.year).takeIf { it >= 1 }
    }

    /** The days a reminder for [date] fires on: from [daysBefore] days before up to the day itself (CAL-016). */
    fun reminderDays(
        date: LocalDate,
        daysBefore: Int,
    ): List<LocalDate> = (daysBefore.coerceAtLeast(0) downTo 0).map { date.minus(it, DateTimeUnit.DAY) }
}
