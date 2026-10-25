package app.lares.server.location

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** An in-process location event (architecture/location.md § Live channel). Published only from `afterCommit`. */
sealed interface LocationHubEvent {
    val householdId: UUID

    /** The member's position, sharing state or presence changed (M4-03). */
    data class MemberChanged(
        override val householdId: UUID,
        val memberId: UUID,
    ) : LocationHubEvent

    /** The visibility rules were replaced (no recorded change goes with it). */
    data class VisibilityChanged(
        override val householdId: UUID,
    ) : LocationHubEvent

    /** A place was created, changed or deleted. */
    data class PlacesChanged(
        override val householdId: UUID,
    ) : LocationHubEvent

    /** A session was revoked (logout, revoke, password reset): its live sockets close (M4-03). */
    data class SessionEnded(
        override val householdId: UUID,
        val sessionId: UUID,
    ) : LocationHubEvent
}

/** The bus the live channel listens to besides `HouseholdChangeBus`; a slow listener loses the oldest events. */
class LocationHub {
    private val flow = MutableSharedFlow<LocationHubEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<LocationHubEvent> = flow.asSharedFlow()

    fun publish(event: LocationHubEvent) {
        flow.tryEmit(event)
    }
}
