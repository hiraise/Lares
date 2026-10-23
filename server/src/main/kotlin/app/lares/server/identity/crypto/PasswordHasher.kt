package app.lares.server.identity.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing (architecture/auth.md § Credentials), stored as a PHC string:
 * `$argon2id$v=19$m=<KiB>,t=<iterations>,p=<parallelism>$<salt>$<hash>` (base64 without padding).
 * Verification reads the parameters from the stored string, so they can be raised later.
 */
class PasswordHasher(
    private val params: Params = Params.DEFAULT,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Params(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    ) {
        companion object {
            val DEFAULT = Params(memoryKib = 19_456, iterations = 2, parallelism = 1)
        }
    }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = argon2(password, salt, params, HASH_BYTES)
        return "\$argon2id\$v=19\$m=${params.memoryKib},t=${params.iterations},p=${params.parallelism}" +
            "\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    /** Returns false for a wrong password and for a malformed stored string. */
    fun verify(
        password: String,
        phc: String,
    ): Boolean {
        val parsed = parse(phc) ?: return false
        val actual = argon2(password, parsed.salt, parsed.params, parsed.hash.size)
        return MessageDigest.isEqual(actual, parsed.hash)
    }

    /**
     * Burns the same time as a real verification. Used when the login is unknown,
     * so response time does not reveal whether it exists.
     */
    fun verifyDummy(password: String) {
        verify(password, dummyHash)
    }

    private val dummyHash: String by lazy { hash("dummy-password-for-timing") }

    internal data class Parsed(
        val params: Params,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    internal companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        private val b64 = Base64.getEncoder().withoutPadding()
        private val b64Decoder = Base64.getDecoder()
        private val paramsPattern = Regex("m=(\\d+),t=(\\d+),p=(\\d+)")

        fun parse(phc: String): Parsed? {
            // "$argon2id$v=19$m=..,t=..,p=..$salt$hash" splits into ["", "argon2id", "v=19", params, salt, hash].
            val parts = phc.split('$')
            if (parts.size != 6 || parts[0].isNotEmpty() || parts[1] != "argon2id" || parts[2] != "v=19") return null
            val match = paramsPattern.matchEntire(parts[3]) ?: return null
            val (m, t, p) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
            if (m <= 0 || t <= 0 || p <= 0) return null
            val salt = runCatching { b64Decoder.decode(parts[4]) }.getOrNull() ?: return null
            val hash = runCatching { b64Decoder.decode(parts[5]) }.getOrNull() ?: return null
            if (salt.isEmpty() || hash.isEmpty()) return null
            return Parsed(Params(m, t, p), salt, hash)
        }

        fun argon2(
            password: String,
            salt: ByteArray,
            params: Params,
            length: Int,
        ): ByteArray {
            // The no-arg Builder defaults to Argon2i; Argon2id must be requested explicitly.
            val parameters =
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(params.iterations)
                    .withMemoryAsKB(params.memoryKib)
                    .withParallelism(params.parallelism)
                    .withSalt(salt)
                    .build()
            val out = ByteArray(length)
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toCharArray(), out)
            return out
        }
    }
}
