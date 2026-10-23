package app.lares.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinksTest {
    @Test
    fun inviteRoundTrip() {
        val payload = DeepLinks.invite("https://family.example.com", "ABCDEFGH1234")
        assertEquals("lares://invite?server=https%3A%2F%2Ffamily.example.com&code=ABCDEFGH1234", payload)
        assertEquals(DeepLink.Invite("https://family.example.com", "ABCDEFGH1234"), DeepLinks.parse(payload))
    }

    @Test
    fun loginRoundTrip() {
        val payload = DeepLinks.login("http://192.168.1.10:8080", "ABCD1234")
        assertEquals(DeepLink.Login("http://192.168.1.10:8080", "ABCD1234"), DeepLinks.parse(payload))
    }

    @Test
    fun encodesReservedAndNonAsciiCharacters() {
        val server = "https://семья.example.com/path with space?x=1&y=+"
        val payload = DeepLinks.login(server, "AB-CD")
        val query = payload.substringAfter('?')
        assertEquals(2, query.split('&').size, "only the separator between parameters stays unescaped: $payload")
        assertEquals(DeepLink.Login(server, "AB-CD"), DeepLinks.parse(payload))
    }

    @Test
    fun parameterOrderAndSchemeCaseDoNotMatter() {
        assertEquals(
            DeepLink.Invite("https://a.example", "XYZ"),
            DeepLinks.parse("Lares://invite?code=XYZ&server=https%3A%2F%2Fa.example"),
        )
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(DeepLinks.parse("https://family.example.com/invite?server=x&code=y"))
        assertNull(DeepLinks.parse("lares://unknown?server=https%3A%2F%2Fa.example&code=XYZ"))
        assertNull(DeepLinks.parse("lares://invite"))
        assertNull(DeepLinks.parse("lares://invite?server=https%3A%2F%2Fa.example"))
        assertNull(DeepLinks.parse("lares://invite?server=https%3A%2F%2Fa.example&code="))
        assertNull(DeepLinks.parse("lares://invite?server=javascript%3Aalert(1)&code=XYZ"))
        assertNull(DeepLinks.parse("lares://invite?server=https%3A%2F%2Fa.example&code=%G1"))
        assertNull(DeepLinks.parse("lares://invite?server=https%3A%2F%2Fa.example&code=AB%2"))
    }
}
