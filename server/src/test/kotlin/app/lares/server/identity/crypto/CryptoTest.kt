package app.lares.server.identity.crypto

import app.lares.contract.Codes
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun hashUsesPhcFormatWithConfiguredParameters() {
        val phc = hasher.hash("secret password")
        assertTrue(phc.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), phc)
        val parsed = assertNotNull(PasswordHasher.parse(phc))
        assertEquals(PasswordHasher.Params.DEFAULT, parsed.params)
        assertEquals(16, parsed.salt.size)
        assertEquals(32, parsed.hash.size)
        val (salt, hash) = phc.split('$').takeLast(2)
        assertFalse(salt.contains('=') || hash.contains('='), "base64 without padding")
    }

    @Test
    fun verifiesCorrectAndRejectsWrongPassword() {
        val phc = hasher.hash("secret password")
        assertTrue(hasher.verify("secret password", phc))
        assertFalse(hasher.verify("secret passworD", phc))
        assertFalse(hasher.verify("", phc))
    }

    @Test
    fun sameSaltNeverRepeats() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"))
    }

    @Test
    fun parametersAreReadFromTheStoredString() {
        // A hash made with other (cheaper) parameters still verifies: parameters can change later.
        val cheap = PasswordHasher(PasswordHasher.Params(memoryKib = 1024, iterations = 1, parallelism = 1))
        val phc = cheap.hash("pw")
        assertTrue(phc.contains("m=1024,t=1,p=1"))
        assertTrue(hasher.verify("pw", phc))
    }

    @Test
    fun matchesReferenceImplementation() {
        // Produced by the reference C implementation (argon2-cffi low_level.hash_secret, type ID) and confirmed with
        // `openssl kdf ... ARGON2ID`: password "password", salt "somesalt", m=1024, t=2, p=1, 32-byte hash.
        val phc = "\$argon2id\$v=19\$m=1024,t=2,p=1\$c29tZXNhbHQ\$7FfsnA6vUe7qLpL/3Kqc3uR48ZJyFbUVt7jWZlf0Htk"
        assertTrue(hasher.verify("password", phc))
        assertFalse(hasher.verify("Password", phc))
        val expected = Base64.getDecoder().decode("7FfsnA6vUe7qLpL/3Kqc3uR48ZJyFbUVt7jWZlf0Htk")
        val actual = PasswordHasher.argon2("password", "somesalt".toByteArray(), PasswordHasher.Params(1024, 2, 1), 32)
        assertEquals(expected.toList(), actual.toList())
    }

    @Test
    fun malformedStoredStringsDoNotVerify() {
        listOf(
            "",
            "plain",
            "\$argon2i\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA",
            "\$argon2id\$v=16\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA",
            "\$argon2id\$v=19\$m=x,t=1,p=1\$c2FsdA\$aGFzaA",
            "\$argon2id\$v=19\$m=1024,t=1,p=1\$!!!\$aGFzaA",
            "\$argon2id\$v=19\$m=1024,t=1,p=1\$c2FsdA",
        ).forEach { phc ->
            assertNull(PasswordHasher.parse(phc), phc)
            assertFalse(hasher.verify("pw", phc), phc)
        }
    }
}

class TokenGeneratorTest {
    @Test
    fun tokensAre32RandomBytesInBase64Url() {
        val generator = TokenGenerator()
        val token = generator.newToken()
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        assertNotEquals(token, generator.newToken())
    }

    @Test
    fun sha256IsStable() {
        val hash = TokenGenerator.sha256("abc")
        assertEquals(32, hash.size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.joinToString("") { "%02x".format(it) })
    }
}

class CodeGeneratorTest {
    @Test
    fun codesUseOnlyTheAlphabet() {
        val generator = CodeGenerator()
        repeat(200) {
            val code = generator.generate(10)
            assertEquals(10, code.length)
            assertTrue(code.all { it in Codes.ALPHABET }, code)
            assertEquals(code, Codes.normalize(code))
        }
    }
}
