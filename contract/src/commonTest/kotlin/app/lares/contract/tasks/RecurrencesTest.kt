package app.lares.contract.tasks

import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrencesTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun rule(
        unit: RecurrenceUnit,
        interval: Int = 1,
        vararg weekdays: DayOfWeek,
    ) = Recurrence(RecurrenceMode.FIXED, unit, interval, weekdays.toSet())

    /** All scheduled dates from [anchor] up to [until], found by walking [Recurrences.firstOnOrAfter]. */
    private fun dates(
        rule: Recurrence,
        anchor: String,
        until: String,
    ): List<String> {
        val result = mutableListOf<String>()
        var next = Recurrences.firstOnOrAfter(rule, d(anchor), d(anchor))
        while (next <= d(until)) {
            result += next.toString()
            next = Recurrences.firstOnOrAfter(rule, d(anchor), next.plus(1, DateTimeUnit.DAY))
        }
        return result
    }

    /** [Recurrences.lastOnOrBefore] agrees with the forward walk on every day of the range. */
    private fun assertConsistent(
        rule: Recurrence,
        anchor: String,
        until: String,
    ) {
        val scheduled = dates(rule, anchor, until).map(::d)
        var day = d(anchor).plus(-3, DateTimeUnit.DAY)
        while (day <= d(until)) {
            val expectedLast = scheduled.lastOrNull { it <= day }
            assertEquals(expectedLast, Recurrences.lastOnOrBefore(rule, d(anchor), day), "last on or before $day")
            val expectedFirst = scheduled.firstOrNull { it >= day }
            if (expectedFirst != null) {
                assertEquals(expectedFirst, Recurrences.firstOnOrAfter(rule, d(anchor), day), "first on or after $day")
            }
            day = day.plus(1, DateTimeUnit.DAY)
        }
    }

    @Test
    fun `TSK-010 every 3 days`() {
        val rule = rule(RecurrenceUnit.DAY, 3)
        assertEquals(listOf("2026-10-23", "2026-10-26", "2026-10-29", "2026-11-01"), dates(rule, "2026-10-23", "2026-11-03"))
        assertConsistent(rule, "2026-10-23", "2026-12-31")
    }

    @Test
    fun `TSK-010 weekly on Monday and Thursday`() {
        val rule = rule(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertEquals(
            listOf("2026-10-19", "2026-10-22", "2026-10-26", "2026-10-29", "2026-11-02"),
            dates(rule, "2026-10-19", "2026-11-02"),
        )
        assertConsistent(rule, "2026-10-19", "2026-12-31")
    }

    @Test
    fun `TSK-010 every 2 weeks with the anchor mid-week`() {
        // Anchor Wednesday 2026-10-21: Monday of that week is already past, Thursday counts; the next week is skipped.
        val rule = rule(RecurrenceUnit.WEEK, 2, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertEquals(
            listOf("2026-10-22", "2026-11-02", "2026-11-05", "2026-11-16", "2026-11-19"),
            dates(rule, "2026-10-21", "2026-11-20"),
        )
        assertNull(Recurrences.lastOnOrBefore(rule, d("2026-10-21"), d("2026-10-21")))
        assertEquals(d("2026-10-22"), Recurrences.lastOnOrBefore(rule, d("2026-10-21"), d("2026-11-01")))
        assertConsistent(rule, "2026-10-21", "2027-03-01")
    }

    @Test
    fun `TSK-010 weekly with only earlier weekdays than the anchor starts next week`() {
        val rule = rule(RecurrenceUnit.WEEK, 3, DayOfWeek.MONDAY)
        assertEquals(d("2026-11-09"), Recurrences.firstOnOrAfter(rule, d("2026-10-21"), d("2026-10-21")))
        assertNull(Recurrences.lastOnOrBefore(rule, d("2026-10-21"), d("2026-11-08")))
        assertConsistent(rule, "2026-10-21", "2027-03-01")
    }

    @Test
    fun `TSK-010 monthly from January 31 clamps to the month end without drift`() {
        val rule = rule(RecurrenceUnit.MONTH)
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31"),
            dates(rule, "2027-01-31", "2027-05-31"),
        )
        assertEquals(listOf("2028-01-31", "2028-02-29", "2028-03-31"), dates(rule, "2028-01-31", "2028-03-31"))
        assertConsistent(rule, "2027-01-31", "2028-12-31")
    }

    @Test
    fun `TSK-010 every 3 months`() {
        val rule = rule(RecurrenceUnit.MONTH, 3)
        assertEquals(
            listOf("2026-11-30", "2027-02-28", "2027-05-30", "2027-08-30", "2027-11-30"),
            dates(rule, "2026-11-30", "2027-12-31"),
        )
        assertEquals(d("2027-02-28"), Recurrences.firstOnOrAfter(rule, d("2026-11-30"), d("2026-12-01")))
        assertEquals(d("2027-02-28"), Recurrences.lastOnOrBefore(rule, d("2026-11-30"), d("2027-05-29")))
        assertConsistent(rule, "2026-11-30", "2028-06-30")
    }

    @Test
    fun `TSK-010 yearly from February 29`() {
        val rule = rule(RecurrenceUnit.YEAR)
        assertEquals(
            listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"),
            dates(rule, "2028-02-29", "2032-03-01"),
        )
        assertConsistent(rule, "2028-02-29", "2033-03-01")
        assertEquals(listOf("2026-05-10", "2028-05-10"), dates(rule(RecurrenceUnit.YEAR, 2), "2026-05-10", "2029-12-31"))
    }

    @Test
    fun `TSK-010 last on or before the first date is null`() {
        for (unit in RecurrenceUnit.entries) {
            val rule = if (unit == RecurrenceUnit.WEEK) rule(unit, 1, DayOfWeek.FRIDAY) else rule(unit)
            assertNull(Recurrences.lastOnOrBefore(rule, d("2026-10-23"), d("2026-10-22")), "$unit")
            assertEquals(d("2026-10-23"), Recurrences.lastOnOrBefore(rule, d("2026-10-23"), d("2026-10-23")), "$unit")
            assertEquals(d("2026-10-23"), Recurrences.firstOnOrAfter(rule, d("2026-10-23"), d("2020-01-01")), "$unit")
        }
        // A week rule whose weekdays do not include the anchor's weekday: the anchor itself is not scheduled.
        val mondays = rule(RecurrenceUnit.WEEK, 1, DayOfWeek.MONDAY)
        assertEquals(d("2026-10-26"), Recurrences.firstOnOrAfter(mondays, d("2026-10-23"), d("2020-01-01")))
        assertNull(Recurrences.lastOnOrBefore(mondays, d("2026-10-23"), d("2026-10-25")))
    }

    @Test
    fun `TSK-011 plus units clamps months and years`() {
        assertEquals(d("2027-02-28"), Recurrences.plusUnits(d("2027-01-31"), RecurrenceUnit.MONTH, 1))
        assertEquals(d("2027-04-30"), Recurrences.plusUnits(d("2027-01-31"), RecurrenceUnit.MONTH, 3))
        assertEquals(d("2029-02-28"), Recurrences.plusUnits(d("2028-02-29"), RecurrenceUnit.YEAR, 1))
        assertEquals(d("2026-11-06"), Recurrences.plusUnits(d("2026-10-23"), RecurrenceUnit.WEEK, 2))
        assertEquals(d("2026-11-02"), Recurrences.plusUnits(d("2026-10-23"), RecurrenceUnit.DAY, 10))
    }

    @Test
    fun `TSK-010 large intervals stay arithmetic`() {
        val weekly = rule(RecurrenceUnit.WEEK, 999, DayOfWeek.SUNDAY)
        assertEquals(d("2026-11-01"), Recurrences.firstOnOrAfter(weekly, d("2026-10-26"), d("2026-10-27")))
        val next = Recurrences.firstOnOrAfter(weekly, d("2026-10-26"), d("2026-11-02"))
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertTrue(next > d("2045-01-01"))
        assertEquals(d("2026-11-01"), Recurrences.lastOnOrBefore(weekly, d("2026-10-26"), d("2040-01-01")))
    }
}
