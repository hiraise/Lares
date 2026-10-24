package app.lares.server.calendar

import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.EventData
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.toContract
import app.lares.server.sync.MutationContext
import app.lares.server.sync.invalidField
import app.lares.server.tasks.TaskRepository
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.UUID
import kotlin.time.Instant

// Server-only checks of the calendar handlers that need the database (architecture/calendar.md § Handlers).

/** The parent event of an occurrence or reminder row: `ENTITY_NOT_FOUND` if missing or elsewhere, `ENTITY_DELETED` if tombstoned. */
internal fun requireParentEvent(
    ctx: MutationContext,
    eventId: String,
): EventData {
    val id = runCatching { UUID.fromString(eventId) }.getOrNull() ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Event not found")
    val event = CalendarRepository.get(ctx.householdId, id) ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Event not found")
    if (event.deleted) throw ApiException(ErrorCode.ENTITY_DELETED, "Event was deleted")
    return event.data
}

/** A child row's id must be the one derived from its parent ([expected]; ADR-0022, ADR-0023), else `id: INVALID_FORMAT`. */
internal fun requireDerivedId(
    id: UUID,
    expected: () -> String,
) {
    val derived = runCatching(expected).getOrNull()
    if (derived == null || id.toString().lowercase() != derived.lowercase()) throw invalidField("id")
}

/**
 * Enqueues the event notices [decide] returns after the transaction commits (CAL-007). [decide] gets the household's live
 * members by person id, `now` and the household zone.
 */
internal fun notifyAfterCommit(
    ctx: MutationContext,
    notifier: EventNotifier,
    eventId: UUID,
    decide: (membersByPerson: Map<String, UUID>, now: Instant, zone: TimeZone) -> List<EventChanges.Notice>,
) {
    val notices =
        decide(CalendarRepository.membersByPerson(ctx.householdId), ctx.now.toContract(), TaskRepository.householdTimeZone(ctx.householdId))
    if (notices.isEmpty()) return
    val actor = UUID.fromString(ctx.viewer.memberId)
    TransactionManager.current().afterCommit {
        notices.forEach { notifier.enqueue(ctx.householdId, eventId, actor, it) }
    }
}
