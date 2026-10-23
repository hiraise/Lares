package app.lares.contract.tasks

import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Scheduled dates of a fixed rule counted from the start date `anchor` (architecture/tasks.md § Scheduled dates).
 * No unbounded loops: arithmetic for day/month/year, at most two weeks of scanning for week rules.
 * Defensive against rules the validators reject: an interval below 1 counts as 1, empty weekdays as the anchor's weekday.
 */
object Recurrences {
    /**
     * The first scheduled date ≥ [date] (searching from the anchor when [date] is before it). The anchor is the first
     * date of day, month and year rules; a week rule starts on the first chosen weekday on or after it.
     */
    fun firstOnOrAfter(
        rule: Recurrence,
        anchor: LocalDate,
        date: LocalDate,
    ): LocalDate {
        val from = maxOf(date, anchor)
        val n = rule.step()
        return when (rule.unit) {
            RecurrenceUnit.DAY -> anchor.plus(ceilDiv(anchor.daysUntil(from), n) * n, DateTimeUnit.DAY)
            RecurrenceUnit.WEEK -> firstWeekly(rule, anchor, from, n)
            RecurrenceUnit.MONTH, RecurrenceUnit.YEAR -> firstMonthBased(rule.unit, anchor, from, n)
        }
    }

    /** The last scheduled date ≤ [date], or `null` if [date] is before the first scheduled date. */
    fun lastOnOrBefore(
        rule: Recurrence,
        anchor: LocalDate,
        date: LocalDate,
    ): LocalDate? {
        if (date < anchor) return null
        val n = rule.step()
        return when (rule.unit) {
            RecurrenceUnit.DAY -> anchor.plus(anchor.daysUntil(date) / n * n, DateTimeUnit.DAY)
            RecurrenceUnit.WEEK -> lastWeekly(rule, anchor, date, n)
            RecurrenceUnit.MONTH, RecurrenceUnit.YEAR -> lastMonthBased(rule.unit, anchor, date, n)
        }
    }

    /** [date] plus [n] units; months and years clamp the day to the end of a shorter month (Jan 31 → Feb 28). */
    fun plusUnits(
        date: LocalDate,
        unit: RecurrenceUnit,
        n: Int,
    ): LocalDate =
        when (unit) {
            RecurrenceUnit.DAY -> date.plus(n, DateTimeUnit.DAY)
            RecurrenceUnit.WEEK -> date.plus(n, DateTimeUnit.WEEK)
            RecurrenceUnit.MONTH -> date.plus(n, DateTimeUnit.MONTH)
            RecurrenceUnit.YEAR -> date.plus(n, DateTimeUnit.YEAR)
        }

    private fun Recurrence.step(): Int = interval.coerceAtLeast(1)

    // Month and year rules: the k-th date is always computed from the anchor, so the day never drifts.

    private fun firstMonthBased(
        unit: RecurrenceUnit,
        anchor: LocalDate,
        date: LocalDate,
        n: Int,
    ): LocalDate {
        var k = ceilDiv(unitsBetween(unit, anchor, date), n)
        var candidate = plusUnits(anchor, unit, k * n)
        if (candidate < date) candidate = plusUnits(anchor, unit, ++k * n)
        return candidate
    }

    private fun lastMonthBased(
        unit: RecurrenceUnit,
        anchor: LocalDate,
        date: LocalDate,
        n: Int,
    ): LocalDate {
        var k = unitsBetween(unit, anchor, date) / n
        var candidate = plusUnits(anchor, unit, k * n)
        if (candidate > date) candidate = plusUnits(anchor, unit, --k * n)
        return candidate
    }

    /** Calendar months or years from [from] to [to], ignoring the day ([to] ≥ [from]). */
    private fun unitsBetween(
        unit: RecurrenceUnit,
        from: LocalDate,
        to: LocalDate,
    ): Int =
        when (unit) {
            RecurrenceUnit.YEAR -> to.year - from.year
            else -> (to.year - from.year) * MONTHS_PER_YEAR + (to.month.ordinal - from.month.ordinal)
        }

    // Week rules: weeks are counted from the Monday of the anchor's week; week k is scheduled when k % n == 0.

    private fun firstWeekly(
        rule: Recurrence,
        anchor: LocalDate,
        date: LocalDate,
        n: Int,
    ): LocalDate {
        val days = rule.days(anchor)
        val week = weekIndex(anchor, date)
        if (week % n == 0) {
            val sunday = mondayOf(date).plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY)
            firstIn(date, sunday, days)?.let { return it }
        }
        val nextWeek = (week / n + 1) * n
        val monday = mondayOf(anchor).plus(nextWeek * DAYS_PER_WEEK, DateTimeUnit.DAY)
        // A whole week contains every weekday, so this always finds one.
        return checkNotNull(firstIn(monday, monday.plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY), days))
    }

    private fun lastWeekly(
        rule: Recurrence,
        anchor: LocalDate,
        date: LocalDate,
        n: Int,
    ): LocalDate? {
        val days = rule.days(anchor)
        val week = weekIndex(anchor, date)
        if (week % n == 0) {
            lastIn(maxOf(mondayOf(date), anchor), date, days)?.let { return it }
        }
        val previousWeek = if (week % n == 0) week - n else week - week % n
        if (previousWeek < 0) return null
        val monday = mondayOf(anchor).plus(previousWeek * DAYS_PER_WEEK, DateTimeUnit.DAY)
        return lastIn(maxOf(monday, anchor), monday.plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY), days)
    }

    private fun Recurrence.days(anchor: LocalDate): Set<DayOfWeek> = weekdays.ifEmpty { setOf(anchor.dayOfWeek) }

    private fun weekIndex(
        anchor: LocalDate,
        date: LocalDate,
    ): Int = mondayOf(anchor).daysUntil(mondayOf(date)) / DAYS_PER_WEEK

    private fun mondayOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    private fun firstIn(
        from: LocalDate,
        to: LocalDate,
        days: Set<DayOfWeek>,
    ): LocalDate? {
        var day = from
        while (day <= to) {
            if (day.dayOfWeek in days) return day
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    private fun lastIn(
        from: LocalDate,
        to: LocalDate,
        days: Set<DayOfWeek>,
    ): LocalDate? {
        var day = to
        while (day >= from) {
            if (day.dayOfWeek in days) return day
            day = day.minus(1, DateTimeUnit.DAY)
        }
        return null
    }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
}
