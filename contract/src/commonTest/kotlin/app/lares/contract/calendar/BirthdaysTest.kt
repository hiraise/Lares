package app.lares.contract.calendar

import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BirthdaysTest {
    @Test
    fun `CAL-004 29 February falls on 28 February in non-leap years`() {
        assertEquals(LocalDate(2028, 2, 29), Birthdays.dateIn(2028, 2, 29))
        assertEquals(LocalDate(2027, 2, 28), Birthdays.dateIn(2027, 2, 29))
        assertEquals(LocalDate(2100, 2, 28), Birthdays.dateIn(2100, 2, 29))
        assertEquals(LocalDate(2027, 12, 31), Birthdays.dateIn(2027, 12, 31))
        assertEquals(LocalDate(2027, 3, 1), Birthdays.onOrAfter(3, 1, LocalDate(2027, 2, 28)))
        assertEquals(LocalDate(2027, 2, 28), Birthdays.onOrAfter(2, 29, LocalDate(2026, 10, 24)))
    }

    @Test
    fun `CAL-004 on or after across New Year`() {
        assertEquals(LocalDate(2027, 1, 5), Birthdays.onOrAfter(1, 5, LocalDate(2026, 12, 30)))
        assertEquals(LocalDate(2026, 12, 30), Birthdays.onOrAfter(12, 30, LocalDate(2026, 12, 30)))
        assertEquals(LocalDate(2027, 12, 29), Birthdays.onOrAfter(12, 29, LocalDate(2026, 12, 30)))
    }

    @Test
    fun `CAL-004 age known, unknown and zero`() {
        assertEquals(78, Birthdays.age(1948, LocalDate(2026, 11, 3)))
        assertNull(Birthdays.age(null, LocalDate(2026, 11, 3)))
        assertNull(Birthdays.age(2026, LocalDate(2026, 11, 3)))
        assertEquals(1, Birthdays.age(2025, LocalDate(2026, 1, 1)))
    }

    @Test
    fun `CAL-005 years of a memorable date`() {
        val wedding =
            EventData(
                "Wedding",
                "m1",
                LocalDate(2010, 6, 12),
                LocalDate(2010, 6, 12),
                allDay = true,
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR),
                kind = EventKind.MEMORABLE_DATE,
                countYears = true,
            )
        assertEquals(16, Birthdays.years(wedding, LocalDate(2026, 6, 12)))
        assertNull(Birthdays.years(wedding, LocalDate(2010, 6, 12)))
        assertNull(Birthdays.years(wedding.copy(countYears = false), LocalDate(2026, 6, 12)))
        assertNull(Birthdays.years(wedding.copy(kind = EventKind.EVENT), LocalDate(2026, 6, 12)))
    }

    @Test
    fun `CAL-016 reminder days from N days before to the day itself`() {
        val day = LocalDate(2027, 1, 2)
        assertEquals(listOf(day), Birthdays.reminderDays(day, 0))
        assertEquals(
            listOf(LocalDate(2026, 12, 30), LocalDate(2026, 12, 31), LocalDate(2027, 1, 1), day),
            Birthdays.reminderDays(day, 3),
        )
    }
}
