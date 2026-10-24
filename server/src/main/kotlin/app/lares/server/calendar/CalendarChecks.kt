package app.lares.server.calendar

import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.EventData
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.MutationContext
import app.lares.server.sync.invalidField
import java.util.UUID

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
