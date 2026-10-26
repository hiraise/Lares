package app.lares.client.data.location

import app.lares.client.data.location.TrackingPolicy.Mode
import app.lares.client.data.tasks.TestClock
import app.lares.contract.api.location.PositionFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TrackingPolicyTest {
    private val start = Instant.parse("2026-10-21T10:00:00Z")
    private val clock = TestClock(start)
    private val policy = TrackingPolicy(clock)

    /** About 1 m of latitude. */
    private val meter = 1.0 / 111_195

    /** A fix [after] the start, [north] metres north of home; the clock moves to its time. */
    private fun fix(
        after: Duration,
        north: Double = 0.0,
        accuracy: Float = 10f,
        speed: Float? = null,
    ): PositionFix {
        clock.now = start + after
        return PositionFix(start + after, HOME.latitude + north * meter, HOME.longitude, accuracy, speedMps = speed)
    }

    /** Fixes every 10 s from [from] to [to] at [north] metres; returns the decisions. */
    private fun standStill(
        from: Duration,
        to: Duration,
        north: Double = 0.0,
        speed: Float? = 0f,
    ): List<TrackingPolicy.Decision> {
        val out = mutableListOf<TrackingPolicy.Decision>()
        var t = from
        while (t <= to) {
            out += policy.onFix(fix(t, north + (t.inWholeSeconds % 3) * 5, speed = speed))
            t += 10.seconds
        }
        return out
    }

    @Test
    fun `LOC-007 moving records a fix every 30 s or every 50 m`() {
        assertEquals(Mode.MOVING, policy.mode, "starts moving")
        assertTrue(policy.onFix(fix(0.seconds)).record, "the first fix")
        assertFalse(policy.onFix(fix(10.seconds, north = 20.0)).record)
        assertTrue(policy.onFix(fix(20.seconds, north = 60.0)).record, "50 m from the last recorded")
        assertFalse(policy.onFix(fix(40.seconds, north = 70.0)).record)
        assertTrue(policy.onFix(fix(50.seconds, north = 80.0)).record, "30 s since the last recorded")
    }

    @Test
    fun `LOC-007 inaccurate fixes are ignored`() {
        assertFalse(policy.onFix(fix(0.seconds, accuracy = 250f)).record)
        assertTrue(policy.onFix(fix(5.seconds, accuracy = 200f)).record)
        policy.onSignificantMotion()
        standStill(10.seconds, 4.minutes)
        assertEquals(Mode.STILL, policy.mode)
        assertFalse(policy.onFix(fix(20.minutes, accuracy = 500f)).record, "also while still")
    }

    @Test
    fun `LOC-007 still after 3 minutes within 75 m, then every fix is a heartbeat`() {
        val decisions = standStill(0.seconds, 3.minutes)
        assertEquals(Mode.STILL, policy.mode)
        assertEquals(listOf(Mode.STILL), decisions.mapNotNull { it.newMode })
        assertEquals(Mode.STILL, decisions.last().newMode, "not before 3 minutes")
        assertEquals(TrackingPolicy.Decision(record = true), policy.onFix(fix(18.minutes, north = 10.0)))
        assertEquals(TrackingPolicy.Decision(record = true), policy.onFix(fix(33.minutes, north = 5.0)))
    }

    @Test
    fun `LOC-007 walking slowly or reporting speed keeps moving`() {
        standStill(0.seconds, 5.minutes, speed = 1.5f)
        assertEquals(Mode.MOVING, policy.mode, "speed of 1 m/s or more")

        val walker = TrackingPolicy(clock)
        var t = 6.minutes
        while (t <= 10.minutes) {
            walker.onFix(fix(t, north = (t - 6.minutes).inWholeSeconds.toDouble()))
            t += 10.seconds
        }
        assertEquals(Mode.MOVING, walker.mode, "1 m/s without a reported speed spreads beyond 75 m")
    }

    @Test
    fun `LOC-007 still to moving by the motion sensor and by a fix 150 m away`() {
        standStill(0.seconds, 3.minutes)
        assertEquals(Mode.MOVING, policy.onSignificantMotion())
        assertNull(policy.onSignificantMotion(), "already moving")

        standStill(4.minutes, 8.minutes)
        assertEquals(Mode.STILL, policy.mode)
        assertNull(policy.onFix(fix(20.minutes, north = 160.0, accuracy = 150f)).newMode, "too inaccurate to leave")
        val away = policy.onFix(fix(21.minutes, north = 170.0, accuracy = 30f))
        assertEquals(TrackingPolicy.Decision(record = true, newMode = Mode.MOVING), away)
    }

    @Test
    fun `LOC-010 paused records nothing and resumes moving`() {
        standStill(0.seconds, 3.minutes)
        assertEquals(Mode.PAUSED, policy.onPause())
        assertFalse(policy.onFix(fix(5.minutes)).record)
        assertNull(policy.onSignificantMotion())
        assertEquals(Mode.MOVING, policy.onResume())
        assertTrue(policy.onFix(fix(6.minutes)).record)
    }

    @Test
    fun `LOC-007 platform requests per mode, SDK level and sensor`() {
        val moving = policy.request(Mode.MOVING, sdk = 34, hasMotionSensor = true)!!
        assertEquals(TrackingRequest(LocationProvider.FUSED, 10.seconds, 5.seconds, RequestQuality.HIGH_ACCURACY, passive = false), moving)
        assertEquals(LocationProvider.GPS, policy.request(Mode.MOVING, sdk = 30, hasMotionSensor = true)!!.provider)
        assertEquals(LocationProvider.GPS, policy.request(Mode.MOVING, sdk = 34, hasMotionSensor = true, hasFused = false)!!.provider)

        val still = policy.request(Mode.STILL, sdk = 35, hasMotionSensor = true)!!
        assertEquals(TrackingRequest(LocationProvider.FUSED, 15.minutes, 5.minutes, RequestQuality.BALANCED, passive = true), still)
        assertEquals(5.minutes, policy.request(Mode.STILL, sdk = 35, hasMotionSensor = false)!!.interval)
        assertEquals(RequestQuality.HIGH_ACCURACY, policy.request(Mode.STILL, sdk = 36, hasMotionSensor = true)!!.quality)
        assertNull(policy.request(Mode.PAUSED, sdk = 36, hasMotionSensor = true))
    }

    @Test
    fun `LOC-007 upload decisions`() {
        val now = clock.now()
        assertFalse(policy.shouldUpload(now - 29.seconds, 3, Mode.MOVING))
        assertTrue(policy.shouldUpload(now - 30.seconds, 3, Mode.MOVING))
        assertTrue(policy.shouldUpload(now, 20, Mode.MOVING))
        assertFalse(policy.shouldUpload(null, 0, Mode.MOVING))
        assertTrue(policy.shouldUpload(now, 1, Mode.STILL))
        assertFalse(policy.shouldUpload(null, 0, Mode.STILL))
        assertFalse(policy.shouldUpload(now - 1.minutes, 50, Mode.PAUSED))
    }
}
