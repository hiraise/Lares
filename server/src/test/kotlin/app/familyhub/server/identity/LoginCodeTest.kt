package app.familyhub.server.identity

import app.familyhub.contract.DeepLink
import app.familyhub.contract.DeepLinks
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.server.testing.ADULTS
import app.familyhub.server.testing.assertError
import app.familyhub.server.testing.createLoginCode
import app.familyhub.server.testing.exchangeLoginCode
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.loginCodeFor
import app.familyhub.server.testing.memberSession
import app.familyhub.server.testing.send
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import app.familyhub.server.testing.testClock
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** FND-014: QR login on a new device. */
class LoginCodeTest {
    @Test
    fun memberCreatesCodeForThemselves() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val code = client.loginCodeFor(adult.accessToken)
            assertEquals(8, code.code.length)
            assertEquals(DeepLink.Login(deps.config.publicUrl, code.code), DeepLinks.parse(code.payload))
            assertEquals(deps.testClock.now() + 5.minutes, code.expiresAt)

            val response = client.exchangeLoginCode(code.code)
            assertEquals(HttpStatusCode.OK, response.status)
            val session = response.body<AuthResult>()
            assertEquals(adult.me.memberId, session.me.memberId)
            assertNotEquals(adult.sessionId, session.sessionId)
            // Explicitly naming oneself is the same as omitting the member.
            assertEquals(HttpStatusCode.Created, client.createLoginCode(adult.accessToken, adult.me.memberId).status)
        }

    @Test
    fun codeForAnotherMemberNeedsHouseholdManage() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val response = client.createLoginCode(adult.accessToken, admin.me.memberId)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertError(ErrorCode.FORBIDDEN)
            // Without the permission even unknown ids are just forbidden.
            client.createLoginCode(adult.accessToken, "not-an-id").assertError(ErrorCode.FORBIDDEN)
            // The administrator may.
            assertEquals(HttpStatusCode.Created, client.createLoginCode(admin.accessToken, adult.me.memberId).status)
        }

    @Test
    fun codeIsSingleUse() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val code = client.loginCodeFor(admin.accessToken)
            assertEquals(HttpStatusCode.OK, client.exchangeLoginCode(code.code).status)
            val again = client.exchangeLoginCode(code.code)
            assertEquals(HttpStatusCode.NotFound, again.status)
            again.assertError(ErrorCode.LOGIN_CODE_INVALID)
            client.exchangeLoginCode("ZZZZZZZZ").assertError(ErrorCode.LOGIN_CODE_INVALID)
        }

    @Test
    fun codeExpiresAfterFiveMinutes() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val fresh = client.loginCodeFor(admin.accessToken)
            val stale = client.loginCodeFor(admin.accessToken)
            deps.testClock.advance(4.minutes + 59.seconds)
            assertEquals(HttpStatusCode.OK, client.exchangeLoginCode(fresh.code).status)
            deps.testClock.advance(1.seconds)
            client.exchangeLoginCode(stale.code).assertError(ErrorCode.LOGIN_CODE_INVALID)
        }

    @Test
    fun codeOfRemovedMemberIsInvalid() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val bob = client.memberSession(admin.accessToken, "bob", emptySet())
            val code = client.loginCodeFor(bob.accessToken)
            client.send(HttpMethod.Delete, "/api/v1/members/${bob.me.memberId}", admin.accessToken)
            client.exchangeLoginCode(code.code).assertError(ErrorCode.LOGIN_CODE_INVALID)
        }
}
