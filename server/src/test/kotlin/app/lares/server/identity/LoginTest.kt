package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.LoginRequest
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.json
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.testDevice
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes

/** FND-010: login with login + password. */
class LoginTest {
    @Test
    fun loginCreatesNewSession() =
        testApp {
            val client = jsonClient()
            val setup = client.setup()
            val response = client.login(ADMIN_LOGIN, ADMIN_PASSWORD)
            assertEquals(HttpStatusCode.OK, response.status)
            val result = response.body<AuthResult>()
            assertNotEquals(setup.sessionId, result.sessionId)
            assertEquals(setup.me, result.me)
        }

    @Test
    fun loginIsCaseInsensitive() =
        testApp {
            val client = jsonClient()
            client.setup()
            assertEquals(HttpStatusCode.OK, client.login(" AdMiN ", ADMIN_PASSWORD).status)
        }

    @Test
    fun wrongPasswordAndUnknownLoginLookTheSame() =
        testApp {
            val client = jsonClient()
            client.setup()
            val wrongPassword = client.login(ADMIN_LOGIN, "wrong password")
            val unknownLogin = client.login("nobody", ADMIN_PASSWORD)
            assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
            assertEquals(HttpStatusCode.Unauthorized, unknownLogin.status)
            assertEquals(wrongPassword.assertError(ErrorCode.INVALID_CREDENTIALS), unknownLogin.assertError(ErrorCode.INVALID_CREDENTIALS))
        }

    @Test
    fun eleventhFailureIsRateLimitedEvenWithCorrectPassword() =
        testApp { deps ->
            val client = jsonClient()
            client.setup()
            repeat(10) { client.login(ADMIN_LOGIN, "wrong password").assertError(ErrorCode.INVALID_CREDENTIALS) }
            val response = client.login(ADMIN_LOGIN, ADMIN_PASSWORD)
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            response.assertError(ErrorCode.RATE_LIMITED)
            // The window slides: after 15 minutes the user can log in again.
            deps.testClock.advance(15.minutes)
            assertEquals(HttpStatusCode.OK, client.login(ADMIN_LOGIN, ADMIN_PASSWORD).status)
        }

    @Test
    fun emptyFieldsAreValidationErrors() =
        testApp {
            val response = jsonClient().post("/api/v1/auth/login") { json(LoginRequest("", "", testDevice.copy(name = ""))) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                setOf("login", "password", "device.name"),
                response
                    .assertError(ErrorCode.VALIDATION_FAILED)
                    .error.fields.keys,
            )
        }
}
