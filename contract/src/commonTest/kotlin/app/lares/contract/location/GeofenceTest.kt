package app.lares.contract.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GeofenceTest {
    private val t0 = Instant.parse("2026-10-25T08:00:00Z")

    private fun t(minutes: Int) = t0 + minutes.minutes

    /** Feeds readings one per minute; returns the final state and the transitions with their times. */
    private fun run(
        start: PresenceState,
        vararg readings: Boolean?,
    ): Pair<PresenceState, List<Pair<Transition, Instant?>>> {
        var state = start
        val transitions = mutableListOf<Pair<Transition, Instant?>>()
        readings.forEachIndexed { i, reading ->
            val (next, transition) = Geofence.step(state, reading, t(i))
            state = next
            if (transition != null) transitions += transition to next.since
        }
        return state to transitions
    }

    private val unknown = PresenceState(inside = null, since = null)
    private val inside = PresenceState(inside = true, since = t0 - 60.minutes)

    @Test
    fun `LOC-022 first reading sets the state without a transition`() {
        val (state, transitions) = run(unknown, null, false)
        assertEquals(PresenceState(inside = false, since = t(1)), state)
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `LOC-022 two outside readings confirm leaving at the first of them`() {
        val (state, transitions) = run(inside, false, false)
        assertEquals(listOf(Transition.LEFT to t(0)), transitions)
        assertEquals(PresenceState(inside = false, since = t(0)), state)
        val (_, arrived) = run(state, true, null, true)
        assertEquals(listOf(Transition.ARRIVED to t(0)), arrived)
    }

    @Test
    fun `LOC-022 one outside reading then inside again does not transition`() {
        val (state, transitions) = run(inside, false, true, false)
        assertTrue(transitions.isEmpty())
        assertEquals(true, state.inside)
        assertEquals(false, state.candidate)
        assertEquals(t(2), state.candidateSince)
    }

    @Test
    fun `LOC-022 the edge zone keeps the state and a pending candidate`() {
        val (pending, none) = run(inside, false, null, null)
        assertTrue(none.isEmpty())
        assertEquals(false, pending.candidate)
        assertEquals(t(0), pending.candidateSince)
        val (_, left) = run(pending, false)
        assertEquals(listOf(Transition.LEFT to t(0)), left)
    }

    @Test
    fun `LOC-022 readings, accuracy and margins`() {
        assertEquals(25.0, Geofence.margin(50))
        assertEquals(100.0, Geofence.margin(500))
        assertEquals(true, Geofence.reading(100.0, 30f, 100))
        assertNull(Geofence.reading(150.0, 30f, 100)) // 120 is not beyond 100 + 25
        assertEquals(false, Geofence.reading(160.0, 30f, 100))
        assertNull(Geofence.reading(600.0, 10f, 500))
        assertEquals(false, Geofence.reading(611.0, 10f, 500))
        assertTrue(Geofence.usable(100f, 50))
        assertFalse(Geofence.usable(101f, 50))
        assertTrue(Geofence.usable(300f, 300))
        assertFalse(Geofence.usable(301f, 300))
    }
}
