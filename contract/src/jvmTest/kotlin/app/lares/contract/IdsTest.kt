package app.lares.contract

import kotlinx.datetime.LocalDate
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
    fun `derived ids are version 8, RFC variant, canonical lowercase`() {
        val ids = listOf(Ids.occurrenceRowId(event, LocalDate(2026, 10, 28)), Ids.reminderRowId(event, member))
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
