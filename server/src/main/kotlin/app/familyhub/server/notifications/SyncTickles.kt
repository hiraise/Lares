package app.familyhub.server.notifications

import app.familyhub.server.platform.sync.HouseholdChangeBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Per-household throttle for sync tickles: at most one push per [interval], with a trailing send, so changes during
 * a window always produce a push at its end. Pure state; time comes from the caller.
 */
class TickleThrottle(
    private val interval: Duration = 30.seconds,
) {
    private class State(
        var lastSentAt: Instant,
        var pendingSeq: Long? = null,
    )

    private val states = HashMap<UUID, State>()

    /** A change happened; returns the seq to send right now, or `null` if the send is deferred to the window's end. */
    @Synchronized
    fun onChange(
        householdId: UUID,
        seq: Long,
        now: Instant,
    ): Long? {
        val state = states[householdId]
        if (state == null || now - state.lastSentAt >= interval) {
            states[householdId] = State(lastSentAt = now)
            return seq
        }
        state.pendingSeq = maxOf(state.pendingSeq ?: seq, seq)
        return null
    }

    /** Deferred sends whose window has ended, as household → newest seq. Forgets households that are idle. */
    @Synchronized
    fun due(now: Instant): Map<UUID, Long> {
        val result = mutableMapOf<UUID, Long>()
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (householdId, state) = iterator.next()
            if (now - state.lastSentAt < interval) continue
            val seq = state.pendingSeq
            if (seq == null) {
                iterator.remove()
            } else {
                result[householdId] = seq
                state.lastSentAt = now
                state.pendingSeq = null
            }
        }
        return result
    }
}

/**
 * Sends content-free `sync` pushes after household changes (architecture/sync.md § Realtime events), so phones in the
 * background sync soon. Throttled per household by [TickleThrottle].
 */
class SyncTicklePublisher(
    private val bus: HouseholdChangeBus,
    private val clock: Clock,
    private val throttle: TickleThrottle,
    private val send: suspend (householdId: UUID, seq: Long) -> Unit,
    private val checkEvery: Duration = 1.seconds,
) {
    private val log = KotlinLogging.logger {}

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            launch {
                bus.events.collect { event ->
                    throttle.onChange(event.householdId, event.seq, clock.now())?.let { launchSend(this@launch, event.householdId, it) }
                }
            }
            while (isActive) {
                delay(checkEvery)
                throttle.due(clock.now()).forEach { (householdId, seq) -> launchSend(this, householdId, seq) }
            }
        }

    private fun launchSend(
        scope: CoroutineScope,
        householdId: UUID,
        seq: Long,
    ) {
        scope.launch {
            try {
                send(householdId, seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Sync tickle for household $householdId failed" }
            }
        }
    }
}
