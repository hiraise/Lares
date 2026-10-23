package app.lares.server.identity.crypto

import app.lares.contract.Codes
import java.security.SecureRandom

/** Generates setup, invite and login codes from the Crockford alphabet (architecture/auth.md § Codes). */
class CodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    /** Returns a normalized code (no dash) of [length] characters. */
    fun generate(length: Int): String =
        buildString(length) {
            repeat(length) { append(Codes.ALPHABET[random.nextInt(Codes.ALPHABET.length)]) }
        }

    companion object {
        /** Invite and login codes are stored and compared as the SHA-256 of their normalized form. */
        fun hash(code: String): ByteArray = TokenGenerator.sha256(Codes.normalize(code))
    }
}
