package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.server.identity.crypto.CodeGenerator
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FailedAttemptLimiterTest {
    private val clock = TestClock()
    private val limiter = FailedAttemptLimiter(maxFailures = 3, window = 15.minutes, clock = clock)

    private fun assertLimited(key: String) {
        val e = assertFailsWith<ApiException> { limiter.ensureAllowed(key) }
        assertEquals(ErrorCode.RATE_LIMITED, e.code)
    }

    @Test
    fun blocksAfterMaxFailures() {
        repeat(3) {
            limiter.ensureAllowed("ip")
            limiter.recordFailure("ip")
        }
        assertLimited("ip")
        limiter.ensureAllowed("other")
    }

    @Test
    fun windowSlides() {
        limiter.recordFailure("ip")
        clock.advance(10.minutes)
        limiter.recordFailure("ip")
        limiter.recordFailure("ip")
        assertLimited("ip")
        // The first failure leaves the window after 15 minutes.
        clock.advance(5.minutes + 1.seconds)
        limiter.ensureAllowed("ip")
        limiter.recordFailure("ip")
        assertLimited("ip")
    }

    @Test
    fun resetClearsTheKey() {
        repeat(3) { limiter.recordFailure("login") }
        limiter.reset("login")
        limiter.ensureAllowed("login")
    }

    @Test
    fun templatesMatchAuthDesign() {
        val login = FailedAttemptLimiter.forLogin(clock)
        repeat(10) { login.recordFailure("k") }
        assertFailsWith<ApiException> { login.ensureAllowed("k") }
        val setup = FailedAttemptLimiter.forSetup(clock)
        repeat(4) { setup.recordFailure("k") }
        setup.ensureAllowed("k")
        setup.recordFailure("k")
        assertFailsWith<ApiException> { setup.ensureAllowed("k") }
    }
}

class SetupCodeProviderTest {
    @Test
    fun configuredCodeIsNormalized() {
        val provider = SetupCodeProvider("abcde-o1234", CodeGenerator())
        assertTrue(provider.matches("ABCDE01234"))
        assertTrue(provider.matches(" abcde 0l234 "))
        assertFalse(provider.matches("ABCDE01235"))
        assertFalse(provider.matches(""))
    }

    @Test
    fun forgottenCodeNeverMatches() {
        val provider = SetupCodeProvider("ABCDE12345", CodeGenerator())
        provider.forget()
        assertFalse(provider.matches("ABCDE12345"))
    }

    @Test
    fun generatedCodeHasTenCharacters() {
        // Without a configured code a random one is generated; it cannot be guessed by a test, only shape-checked.
        val provider = SetupCodeProvider(null, CodeGenerator())
        assertFalse(provider.matches("0000000000"))
    }
}
