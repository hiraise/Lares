package app.lares.server.calendar

import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.calendar.EventOccurrence
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.household.MemberRepository
import app.lares.server.household.PermissionService
import app.lares.server.notifications.NotificationService
import app.lares.server.notifications.NotificationTexts
import app.lares.server.platform.db.AppDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Event notifications (CAL-007, architecture/calendar.md § Notifications). The calendar handlers decide what to tell
 * whom with `EventChanges` and [enqueue] events through `afterCommit`; a consumer coroutine ([start]) drops the actor,
 * deleted members and members without `calendar.view`, and sends one `event.changed` notification per event. Each event
 * carries the occurrence it names, so a deleted event can still be described.
 */
class EventNotifier(
    private val db: AppDatabase,
    private val permissions: PermissionService,
    private val notifications: NotificationService,
) {
    sealed interface Event {
        val householdId: UUID
        val eventId: UUID
        val occurrence: EventOccurrence
        val actorMemberId: UUID
        val memberIds: Set<UUID>
    }

    /** [memberIds] became participants of [occurrence] (or of the event, then its next occurrence). */
    data class Added(
        override val householdId: UUID,
        override val eventId: UUID,
        override val occurrence: EventOccurrence,
        override val actorMemberId: UUID,
        override val memberIds: Set<UUID>,
    ) : Event

    /** The date, time or place of [occurrence] (or of the series) changed. */
    data class Changed(
        override val householdId: UUID,
        override val eventId: UUID,
        override val occurrence: EventOccurrence,
        override val actorMemberId: UUID,
        override val memberIds: Set<UUID>,
    ) : Event

    /** [occurrence] or the whole event was cancelled; [fromDate]: the series ends before [occurrence]. */
    data class Cancelled(
        override val householdId: UUID,
        override val eventId: UUID,
        override val occurrence: EventOccurrence,
        override val actorMemberId: UUID,
        override val memberIds: Set<UUID>,
        val fromDate: Boolean,
    ) : Event

    private val log = KotlinLogging.logger {}
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Events enqueued and not yet processed by the consumer. */
    private val pending = MutableStateFlow(0)

    /** Never blocks; call it from `afterCommit`. */
    fun enqueue(event: Event) {
        pending.update { it + 1 }
        if (events.trySend(event).isFailure) pending.update { it - 1 }
    }

    /** Turns an `EventChanges` notice into an event. */
    fun enqueue(
        householdId: UUID,
        eventId: UUID,
        actorMemberId: UUID,
        notice: EventChanges.Notice,
    ) {
        val occ = notice.occurrence
        val members = notice.memberIds
        enqueue(
            when (notice.kind) {
                EventChanges.Kind.ADDED -> Added(householdId, eventId, occ, actorMemberId, members)
                EventChanges.Kind.CHANGED -> Changed(householdId, eventId, occ, actorMemberId, members)
                EventChanges.Kind.CANCELLED -> Cancelled(householdId, eventId, occ, actorMemberId, members, notice.fromDate)
            },
        )
    }

    /** The consumer: processes events one by one; failures are logged and the loop goes on. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            for (event in events) {
                try {
                    process(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Event notification ${event::class.simpleName} for event ${event.eventId} failed" }
                } finally {
                    pending.update { it - 1 }
                }
            }
        }

    /** Suspends until every enqueued event has been processed (tests use it instead of waiting). */
    suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    suspend fun process(event: Event) {
        val recipients =
            db.dbQuery {
                event.memberIds.filter { memberId ->
                    memberId != event.actorMemberId &&
                        MemberRepository.get(event.householdId, memberId)?.deleted == false &&
                        permissions.effectiveInTransaction(event.householdId, memberId).has(Permission.CALENDAR_VIEW)
                }
            }
        if (recipients.isEmpty()) return
        val occ = event.occurrence
        val results =
            notifications.notify(
                event.householdId,
                recipients,
                NotificationType.EVENT_CHANGED,
                entityType = EntityTypes.EVENT,
                entityId = event.eventId.toString(),
            ) { language ->
                val whenText = EventWhenText.format(language, occ)
                val text =
                    when (event) {
                        is Added -> NotificationTexts.eventAdded(language, occ.title, whenText)
                        is Changed -> NotificationTexts.eventChanged(language, occ.title, whenText)
                        is Cancelled -> NotificationTexts.eventCancelled(language, occ.title, whenText, event.fromDate)
                    }
                text.title to text.body
            }
        log.debug { "${event::class.simpleName} for event ${event.eventId}: ${recipients.size} members, ${results.size} sessions" }
    }
}
