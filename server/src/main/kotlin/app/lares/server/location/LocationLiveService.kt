package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.permissions.Permission
import app.lares.server.household.MemberRepository
import app.lares.server.household.PermissionService
import app.lares.server.identity.MemberPrincipal
import app.lares.server.identity.SessionRepository
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.sync.HouseholdChangeBus
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The live location channel `WS /api/v1/location/live` (architecture/location.md § Live channel, ADR-0024): a snapshot
 * on connect, then `member` for visible members whose data changed, and `gone` + `snapshot` when visibility changed.
 * Messages go only to the connection's member and only about members they may see now.
 */
class LocationLiveService(
    private val db: AppDatabase,
    private val locations: LocationService,
    private val permissions: PermissionService,
    private val hub: LocationHub,
    private val changeBus: HouseholdChangeBus,
    private val debounce: Duration = 500.milliseconds,
    private val recheck: Duration = 60.seconds,
) {
    suspend fun serve(
        session: WebSocketSession,
        principal: MemberPrincipal,
    ) {
        val householdId = principal.householdId
        val viewer = principal.memberId
        if (!permissions.effective(householdId, viewer).has(Permission.LOCATION_VIEW)) {
            session.close(CloseReason(CLOSE_FORBIDDEN, ErrorCode.FORBIDDEN.name))
            return
        }
        coroutineScope {
            val changedMembers = Channel<UUID>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val recompute = Channel<Unit>(Channel.CONFLATED)
            val placesChanged = AtomicBoolean(false)
            val closing = CompletableDeferred<CloseReason>()
            val hubSubscribed = CompletableDeferred<Unit>()
            val busSubscribed = CompletableDeferred<Unit>()
            // Subscribe before the first snapshot is built, so no change falls between the two.
            val hubListener =
                launch {
                    hub.events
                        .onSubscription { hubSubscribed.complete(Unit) }
                        .filter { it.householdId == householdId }
                        .collect { event ->
                            when (event) {
                                is LocationHubEvent.MemberChanged -> {
                                    changedMembers.trySend(event.memberId)
                                }

                                is LocationHubEvent.VisibilityChanged -> {
                                    recompute.trySend(Unit)
                                }

                                is LocationHubEvent.PlacesChanged -> {
                                    placesChanged.set(true)
                                    recompute.trySend(Unit)
                                }

                                is LocationHubEvent.SessionEnded -> {
                                    if (event.sessionId == principal.sessionId) {
                                        closing.complete(CloseReason(CLOSE_SIGNED_OUT, ErrorCode.SESSION_REVOKED.name))
                                    }
                                }
                            }
                        }
                }
            // Groups, memberships and member removals change through recorded changes.
            val busListener =
                launch {
                    changeBus.events
                        .onSubscription { busSubscribed.complete(Unit) }
                        .filter { it.householdId == householdId }
                        .collect { recompute.trySend(Unit) }
                }
            hubSubscribed.await()
            busSubscribed.await()

            var (levels, now) = db.dbQuery { locations.snapshot(this, householdId, viewer) }
            session.send(frame(LiveMessage(LiveMessage.SNAPSHOT, now = now)))

            val worker =
                launch {
                    while (true) {
                        select {
                            changedMembers.onReceive { memberId ->
                                // A fresh level for every message: cached levels are never trusted for sending.
                                val member = db.dbQuery { locations.memberFor(this, householdId, viewer, memberId) }
                                if (member != null) session.send(frame(LiveMessage(LiveMessage.MEMBER, member = member)))
                            }
                            recompute.onReceive {
                                delay(debounce)
                                recompute.tryReceive()
                                val places = placesChanged.getAndSet(false)
                                val (newLevels, snapshot) = db.dbQuery { locations.snapshot(this, householdId, viewer) }
                                if (newLevels != levels || places) {
                                    val visibleNow = newLevels.filterValues { it >= VisibilityLevel.CURRENT }.keys
                                    levels
                                        .filterValues { it >= VisibilityLevel.CURRENT }
                                        .keys
                                        .filterNot { it in visibleNow }
                                        .forEach { session.send(frame(LiveMessage(LiveMessage.GONE, memberId = it.toString()))) }
                                    session.send(frame(LiveMessage(LiveMessage.SNAPSHOT, now = snapshot)))
                                    levels = newLevels
                                }
                            }
                        }
                    }
                }
            val watchdog =
                launch {
                    while (true) {
                        delay(recheck)
                        if (!alive(principal)) closing.complete(CloseReason(CLOSE_SIGNED_OUT, ErrorCode.SESSION_REVOKED.name))
                    }
                }
            val closer = launch { session.close(closing.await()) }
            try {
                // Clients send nothing; reading keeps the socket alive until it closes.
                for (frame in session.incoming) Unit
            } finally {
                closer.cancel()
                watchdog.cancel()
                worker.cancel()
                busListener.cancel()
                hubListener.cancel()
            }
        }
    }

    private suspend fun alive(principal: MemberPrincipal): Boolean =
        db.dbQuery {
            val session = SessionRepository.get(principal.sessionId)
            val member = MemberRepository.get(principal.householdId, principal.memberId)
            session != null && session.revokedAt == null && member != null && !member.deleted
        }

    private fun frame(message: LiveMessage) = Frame.Text(LaresJson.encodeToString(LiveMessage.serializer(), message))

    companion object {
        /** The session ended or the member was removed. */
        const val CLOSE_SIGNED_OUT: Short = 4401

        /** The member has no `location.view`. */
        const val CLOSE_FORBIDDEN: Short = 4403
    }
}
