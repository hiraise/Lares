package app.familyhub.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class CodesTest {
    @Test
    fun alphabetIsCrockfordBase32() {
        assertEquals(32, Codes.ALPHABET.length)
        assertEquals(32, Codes.ALPHABET.toSet().size)
        listOf('I', 'L', 'O', 'U').forEach { assertEquals(false, it in Codes.ALPHABET) }
    }

    @Test
    fun normalizeHandlesUserInput() {
        assertEquals("ABC0123", Codes.normalize("abc-o12 3"))
        assertEquals("1111", Codes.normalize("iIlL"))
        assertEquals("0000", Codes.normalize("oO0-0"))
        assertEquals("ABCDE12345", Codes.normalize(" abcde - 12345 "))
    }

    @Test
    fun formatAddsDashInTheMiddle() {
        assertEquals("ABCDE-12345", Codes.format("ABCDE12345"))
        assertEquals("ABCD-1234", Codes.format("ABCD1234"))
    }
}
