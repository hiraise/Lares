package app.lares.client.auth

import app.lares.contract.api.auth.AuthResult
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/** The token pair of the current session (architecture/auth.md § Sessions and tokens). */
@Serializable
data class Tokens(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val sessionId: String,
) {
    companion object {
        fun from(result: AuthResult) =
            Tokens(result.accessToken, result.accessExpiresAt, result.refreshToken, result.refreshExpiresAt, result.sessionId)
    }
}

/**
 * Secure storage of the token pair. Android keeps it in DataStore encrypted with an Android Keystore key;
 * the JVM uses memory only.
 */
interface TokenStore {
    suspend fun load(): Tokens?

    suspend fun save(tokens: Tokens)

    suspend fun clear()
}

class InMemoryTokenStore : TokenStore {
    @Volatile private var tokens: Tokens? = null

    override suspend fun load(): Tokens? = tokens

    override suspend fun save(tokens: Tokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
