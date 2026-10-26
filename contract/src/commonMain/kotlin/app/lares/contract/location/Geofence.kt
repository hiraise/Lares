package app.lares.contract.location

import kotlin.math.max
import kotlin.time.Instant

enum class Transition { ARRIVED, LEFT }

/** Whether a member is inside one place, with a reading waiting for confirmation (hysteresis, LOC-022). */
data class PresenceState(
    /** Null = unknown (new place, new member, place moved). */
    val inside: Boolean?,
    val since: Instant?,
    /** A reading that differs from [inside], waiting for a second one. */
    val candidate: Boolean? = null,
    val candidateSince: Instant? = null,
)

/** Arrive / leave detection on the server (architecture/location.md § Pure logic). Fixes are fed in `recordedAt` order. */
object Geofence {
    /** Extra distance beyond the radius before "outside" counts. */
    fun margin(radiusM: Int): Double = max(MIN_MARGIN_M, MARGIN_SHARE * radiusM)

    /** Fixes less accurate than this are skipped for the place. */
    fun usable(
        accuracyM: Float,
        radiusM: Int,
    ): Boolean = accuracyM <= max(radiusM, MIN_USABLE_ACCURACY_M)

    /** True: inside; false: clearly outside even with the inaccuracy; null: the edge zone, no information. */
    fun reading(
        distanceM: Double,
        accuracyM: Float,
        radiusM: Int,
    ): Boolean? =
        when {
            distanceM <= radiusM -> true
            distanceM - accuracyM > radiusM + margin(radiusM) -> false
            else -> null
        }

    /**
     * Applies one reading. The first knowledge never transitions; a change needs two consecutive agreeing readings and
     * then dates back to the first of them (the transition happened at the returned state's `since`).
     */
    fun step(
        state: PresenceState,
        reading: Boolean?,
        at: Instant,
    ): Pair<PresenceState, Transition?> {
        if (reading == null) return state to null
        if (state.inside == null) return PresenceState(inside = reading, since = at) to null
        if (reading == state.inside) return state.copy(candidate = null, candidateSince = null) to null
        if (state.candidate != reading) return state.copy(candidate = reading, candidateSince = at) to null
        val since = state.candidateSince ?: at
        val transition = if (reading) Transition.ARRIVED else Transition.LEFT
        return PresenceState(inside = reading, since = since) to transition
    }

    private const val MIN_MARGIN_M = 25.0
    private const val MARGIN_SHARE = 0.2
    private const val MIN_USABLE_ACCURACY_M = 100
}
