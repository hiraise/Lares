package app.familyhub.server.identity.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Opaque session tokens: 32 random bytes, base64url. Only their SHA-256 hashes are stored (architecture/auth.md). */
class TokenGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    companion object {
        const val TOKEN_BYTES = 32

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
