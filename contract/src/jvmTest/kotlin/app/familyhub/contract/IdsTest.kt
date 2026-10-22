package app.familyhub.contract

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
