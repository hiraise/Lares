package app.lares.contract

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun newIdIsUuidVersion7() {
        val id = Ids.newId()
        val uuid = UUID.fromString(id)
        assertEquals(id, uuid.toString())
        assertEquals(7, uuid.version())
    }

    @Test
    fun idsGeneratedInSequenceSortAscending() {
        // Many ids share one millisecond; ordering must hold within it too.
        val ids = List(1_000) { Ids.newId() }
        ids.zipWithNext().forEach { (first, second) ->
            assertTrue(first < second, "expected $first < $second")
        }
    }

    // Golden values: also checked by tools/dev/seed.py (M3-04); computed independently with Python's uuid module.
    private val event = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
    private val member = "0192f3a4-0000-7000-8000-00000000c0de"

    @Test
    fun `CAL-015 occurrence row ids are stable`() {
        assertEquals("0192f3a4-5b6c-8d8e-9f01-23456789fadf", Ids.occurrenceRowId(event, LocalDate(2026, 10, 28)))
        assertEquals("0192f3a4-5b6c-8d8e-a0fe-dcba98765432", Ids.occurrenceRowId(event, LocalDate(1969, 12, 31)))
        assertEquals(Ids.occurrenceRowId(event, LocalDate(2026, 10, 28)), Ids.occurrenceRowId(event.uppercase(), LocalDate(2026, 10, 28)))
    }

    @Test
    fun `CAL-006 reminder row ids are stable`() {
        assertEquals("0192f3a4-5b6c-8dd4-9f01-234567896b13", Ids.reminderRowId(event, member))
    }

    @Test
    fun `LOC-021 place alert row ids are stable and differ from reminder ids`() {
        // memberId = event, subscriberId = member: same inputs as the reminder vector, different marker.
        assertEquals("0192f3a4-5b6c-8db2-9f01-234567896b13", Ids.placeAlertRowId(event, member))
        assertEquals(Ids.placeAlertRowId(event, member), Ids.placeAlertRowId(event, member))
        assertNotEquals(Ids.reminderRowId(event, member), Ids.placeAlertRowId(event, member))
        assertNotEquals(Ids.placeAlertRowId(event, member), Ids.placeAlertRowId(member, event))
        assertFailsWith<IllegalArgumentException> { Ids.placeAlertRowId(event, "m1") }
    }

    private val december = YearMonth(2026, 12)

    @Test
    fun `BUD-004 BUD-011 BUD-012 BUD-013 budget row ids are stable (same vectors in seed py)`() {
        // The same id as household and income: only the markers tell the rows apart.
        assertEquals("0192f3a4-5b6c-8daf-9f01-23456789f4ce", Ids.budgetPlanRowId(event, december))
        assertEquals("0192f3a4-5b6c-8dac-9f01-23456789f4ce", Ids.budgetMonthRowId(event, december))
        assertEquals("0192f3a4-5b6c-8dad-9f01-23456789f4ce", Ids.budgetReceiptRowId(event, december))
        assertEquals("0192f3a4-5b6c-8dad-9f01-23456789f679", Ids.budgetReceiptRowId(event, YearMonth(1999, 1)))
        assertEquals("0192f3a4-5b6c-8d89-9f01-234567893410", Ids.budgetFundingRowId(event, member, december))
        assertEquals(Ids.budgetPlanRowId(event, december), Ids.budgetPlanRowId(event.uppercase(), december))
    }

    @Test
    fun `BUD-012 budget row ids are distinct by kind, month and category`() {
        val category = Ids.newId()
        assertNotEquals(Ids.budgetPlanRowId(event, december), Ids.budgetMonthRowId(event, december))
        assertNotEquals(Ids.budgetReceiptRowId(event, december), Ids.budgetFundingRowId(event, member, december))
        assertNotEquals(Ids.budgetFundingRowId(event, member, december), Ids.budgetFundingRowId(event, category, december))
        assertNotEquals(Ids.budgetReceiptRowId(event, december), Ids.budgetReceiptRowId(event, YearMonth(2027, 1)))
        val months = (0 until 1_200).map { YearMonth(2000 + it / 12, it % 12 + 1) }
        val all =
            months.flatMap {
                listOf(
                    Ids.budgetPlanRowId(event, it),
                    Ids.budgetMonthRowId(event, it),
                    Ids.budgetReceiptRowId(event, it),
                    Ids.budgetFundingRowId(event, member, it),
                )
            }
        assertEquals(all.size, all.toSet().size)
        assertFailsWith<IllegalArgumentException> { Ids.budgetFundingRowId(event, "c1", december) }
    }

    @Test
    fun `derived ids are version 8, RFC variant, canonical lowercase`() {
        val ids =
            listOf(
                Ids.occurrenceRowId(event, LocalDate(2026, 10, 28)),
                Ids.reminderRowId(event, member),
                Ids.placeAlertRowId(event, member),
                Ids.budgetPlanRowId(event, december),
                Ids.budgetMonthRowId(event, december),
                Ids.budgetReceiptRowId(event, december),
                Ids.budgetFundingRowId(event, member, december),
            )
        for (id in ids) {
            val uuid = UUID.fromString(id)
            assertEquals(id, uuid.toString())
            assertEquals(8, uuid.version())
            assertEquals(2, uuid.variant())
        }
    }

    @Test
    fun `derived ids differ by date and never collide with the reminder id`() {
        val start = LocalDate(2026, 1, 1)
        val reminder = Ids.reminderRowId(event, member)
        val occurrenceIds =
            (0 until 1_000).map { day ->
                Ids.occurrenceRowId(event, LocalDate.fromEpochDays(start.toEpochDays() + day))
            }
        assertEquals(1_000, occurrenceIds.toSet().size)
        assertTrue(reminder !in occurrenceIds)
        assertNotEquals(Ids.reminderRowId(event, member), Ids.reminderRowId(event, Ids.newId()))
    }

    @Test
    fun `invalid input ids throw`() {
        assertFailsWith<IllegalArgumentException> { Ids.occurrenceRowId("not-a-uuid", LocalDate(2026, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { Ids.reminderRowId(event, "m1") }
    }
}
