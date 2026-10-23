package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.RefreshRequest
import app.lares.contract.api.auth.SessionInfo
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.bearer
import app.lares.server.testing.json
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** FND-011: access and refresh tokens, rotation, revocation, session list. */
class SessionTokensTest {
    private suspend fun HttpClient.me(token: String): HttpResponse = get("/api/v1/me") { bearer(token) }

    private suspend fun HttpClient.refresh(token: String): HttpResponse = post("/api/v1/auth/refresh") { json(RefreshRequest(token)) }

    @Test
    fun accessTokenWorksAndBadTokensAreUnauthenticated() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            assertEquals(HttpStatusCode.OK, client.me(auth.accessToken).status)
            client.get("/api/v1/me").assertError(ErrorCode.UNAUTHENTICATED)
            client.me("not-a-token").assertError(ErrorCode.UNAUTHENTICATED)
            client.get("/api/v1/me") { header("Authorization", "Basic abc") }.assertError(ErrorCode.UNAUTHENTICATED)
        }

    @Test
    fun expiredAccessTokenAsksForRefresh() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            deps.testClock.advance(61.minutes)
            val response = client.me(auth.accessToken)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            response.assertError(ErrorCode.SESSION_EXPIRED)
        }

    @Test
    fun refreshRotatesTokens() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            deps.testClock.advance(61.minutes)
            val refreshed = client.refresh(auth.refreshToken).body<AuthResult>()
            assertEquals(auth.sessionId, refreshed.sessionId)
            assertNotEquals(auth.refreshToken, refreshed.refreshToken)
            assertEquals(HttpStatusCode.OK, client.me(refreshed.accessToken).status)
            // The replaced access token is gone.
            client.me(auth.accessToken).assertError(ErrorCode.UNAUTHENTICATED)
        }

    @Test
    fun reusedRefreshTokenRevokesTheSession() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            val refreshed = client.refresh(auth.refreshToken).body<AuthResult>()
            // Outside the grace window the old token counts as stolen.
            deps.testClock.advance(61.seconds)
            client.refresh(auth.refreshToken).assertError(ErrorCode.SESSION_REVOKED)
            client.me(refreshed.accessToken).assertError(ErrorCode.SESSION_REVOKED)
            client.refresh(refreshed.refreshToken).assertError(ErrorCode.SESSION_REVOKED)
        }

    @Test
    fun lostRefreshResponseIsRecoveredWithinGraceWindow() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            val lost = client.refresh(auth.refreshToken).body<AuthResult>()
            deps.testClock.advance(30.seconds)
            // The client never saw `lost` and retries with the old refresh token.
            val retried = client.refresh(auth.refreshToken).body<AuthResult>()
            assertEquals(auth.sessionId, retried.sessionId)
            assertEquals(HttpStatusCode.OK, client.me(retried.accessToken).status)
            client.me(lost.accessToken).assertError(ErrorCode.UNAUTHENTICATED)
            assertEquals(HttpStatusCode.OK, client.refresh(retried.refreshToken).status)
        }

    @Test
    fun oldRefreshTokenAfterNewAccessTokenWasUsedRevokes() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            val refreshed = client.refresh(auth.refreshToken).body<AuthResult>()
            assertEquals(HttpStatusCode.OK, client.me(refreshed.accessToken).status)
            client.refresh(auth.refreshToken).assertError(ErrorCode.SESSION_REVOKED)
            client.me(refreshed.accessToken).assertError(ErrorCode.SESSION_REVOKED)
        }

    @Test
    fun unknownOrExpiredRefreshToken() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            client.refresh("unknown").assertError(ErrorCode.UNAUTHENTICATED)
            deps.testClock.advance(AuthService.REFRESH_TTL)
            client.refresh(auth.refreshToken).assertError(ErrorCode.SESSION_REVOKED)
        }

    @Test
    fun logoutRevokesTheSession() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout") { bearer(auth.accessToken) }.status)
            client.me(auth.accessToken).assertError(ErrorCode.SESSION_REVOKED)
            client.refresh(auth.refreshToken).assertError(ErrorCode.SESSION_REVOKED)
        }

    @Test
    fun sessionListMarksCurrentAndOtherSessionsCanBeRevoked() =
        testApp { deps ->
            val client = jsonClient()
            val first = client.setup()
            deps.testClock.advance(2.minutes)
            val second = client.login(ADMIN_LOGIN, ADMIN_PASSWORD).body<AuthResult>()

            val sessions = client.get("/api/v1/me/sessions") { bearer(first.accessToken) }.body<List<SessionInfo>>()
            assertEquals(setOf(first.sessionId, second.sessionId), sessions.map { it.id }.toSet())
            assertEquals(first.sessionId, sessions.single { it.current }.id)
            assertEquals("Pixel 9", sessions.first().deviceName)

            val revoke = client.delete("/api/v1/me/sessions/${second.sessionId}") { bearer(first.accessToken) }
            assertEquals(HttpStatusCode.NoContent, revoke.status)
            client.me(second.accessToken).assertError(ErrorCode.SESSION_REVOKED)
            assertEquals(HttpStatusCode.OK, client.me(first.accessToken).status)
            val remaining = client.get("/api/v1/me/sessions") { bearer(first.accessToken) }.body<List<SessionInfo>>()
            assertEquals(listOf(first.sessionId), remaining.map { it.id })
        }

    @Test
    fun revokingUnknownSessionIsNotFound() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            client.delete("/api/v1/me/sessions/${UUID.randomUUID()}") { bearer(auth.accessToken) }.assertError(ErrorCode.NOT_FOUND)
            client.delete("/api/v1/me/sessions/not-a-uuid") { bearer(auth.accessToken) }.assertError(ErrorCode.NOT_FOUND)
        }

    @Test
    fun lastSeenIsUpdatedAtMostOncePerMinute() =
        testApp { deps ->
            val client = jsonClient()
            val auth = client.setup()
            val created = deps.testClock.now()
            deps.testClock.advance(30.seconds)
            client.me(auth.accessToken)

            suspend fun lastSeen() =
                client
                    .get("/api/v1/me/sessions") { bearer(auth.accessToken) }
                    .body<List<SessionInfo>>()
                    .single()
                    .lastSeenAt
            assertEquals(created, lastSeen())
            deps.testClock.advance(31.seconds)
            client.me(auth.accessToken)
            assertNotNull(lastSeen()).also { assertEquals(deps.testClock.now(), it) }
        }
}
