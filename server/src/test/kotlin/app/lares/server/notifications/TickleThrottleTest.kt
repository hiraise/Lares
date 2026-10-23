package app.lares.server.notifications

import app.lares.server.testing.TestClock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Sync tickles: at most one push per 30 s per household, with a trailing send (plan M0-09). */
class TickleThrottleTest {
    private val clock = TestClock()
    private val throttle = TickleThrottle(30.seconds)
    private val home = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun firstChangeIsSentAtOnceAndLaterOnesAtTheWindowEnd() {
        assertEquals(1L, throttle.onChange(home, 1, clock.now()))
        clock.advance(5.seconds)
        assertNull(throttle.onChange(home, 2, clock.now()))
        assertNull(throttle.onChange(home, 3, clock.now()))
        clock.advance(20.seconds)
        assertTrue(throttle.due(clock.now()).isEmpty())

        clock.advance(5.seconds)
        assertEquals(mapOf(home to 3L), throttle.due(clock.now()))
        // The trailing send opened a new window.
        clock.advance(10.seconds)
        assertNull(throttle.onChange(home, 4, clock.now()))
        clock.advance(20.seconds)
        assertEquals(mapOf(home to 4L), throttle.due(clock.now()))
    }

    @Test
    fun householdsAreIndependentAndIdleOnesAreForgotten() {
        assertEquals(1L, throttle.onChange(home, 1, clock.now()))
        assertEquals(7L, throttle.onChange(other, 7, clock.now()))
        assertNull(throttle.onChange(home, 2, clock.now()))
        clock.advance(30.seconds)
        assertEquals(mapOf(home to 2L), throttle.due(clock.now()))
        clock.advance(30.seconds)
        assertTrue(throttle.due(clock.now()).isEmpty())
        assertEquals(3L, throttle.onChange(home, 3, clock.now()))
    }
}
