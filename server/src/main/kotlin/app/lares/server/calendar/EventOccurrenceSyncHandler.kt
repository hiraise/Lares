package app.lares.server.calendar

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireAddedPersons
import app.lares.server.sync.requireDerivedId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Changed or cancelled occurrences of a repeating event (CAL-003, CAL-015, ADR-0022). The row id is derived from the
 * event id and the occurrence date, so offline edits of one occurrence on two phones merge field by field. Writing needs
 * `canEdit` on the stored parent event. Clients never delete these rows (`WriteRules` rejects it); only the event's
 * cascade tombstones them. Cancelling, moving or adding participants to an upcoming occurrence tells its participants
 * through [EventNotifier] (CAL-007).
 */
class EventOccurrenceSyncHandler(
    private val notifier: EventNotifier,
) : WritableSyncHandler<EventOccurrenceData>(EventOccurrenceData.serializer()) {
    override val type: String = EntityTypes.EVENT_OCCURRENCE

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        EventOccurrenceTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[EventOccurrenceTable.deleted]
            ChangeRow(
                type,
                row[EventOccurrenceTable.id].toString(),
                row[EventOccurrenceTable.seq],
                deleted,
                if (deleted) null else toJson(CalendarRepository.toOccurrenceData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.CALENDAR_VIEW)

    override fun load(id: UUID): StoredEntity<EventOccurrenceData>? =
        EventOccurrenceTable.selectAll().where { EventOccurrenceTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[EventOccurrenceTable.householdId], it[EventOccurrenceTable.deleted], CalendarRepository.toOccurrenceData(it))
        }

    override fun normalize(data: EventOccurrenceData): EventOccurrenceData = Validators.normalizeEventOccurrence(data)

    override fun validate(
        ctx: MutationContext,
        data: EventOccurrenceData,
    ): Map<String, FieldErrorCode> = Validators.eventOccurrence(data, requireParentEvent(ctx, data.eventId))

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: EventOccurrenceData?,
        merged: EventOccurrenceData?,
    ) {
        // The stored parent decides; a changed eventId is rejected by checkUpsert.
        val event = requireParentEvent(ctx, (existing ?: merged ?: return).eventId)
        if (!EventRules.canEdit(ctx.viewer, event)) {
            throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this occurrence")
        }
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: EventOccurrenceData?,
        data: EventOccurrenceData,
    ) {
        if (existing != null) {
            if (existing.eventId != data.eventId) throw invalidField("eventId")
            if (existing.occurrence != data.occurrence) throw invalidField("occurrence")
        }
        requireDerivedId(id) { Ids.occurrenceRowId(data.eventId, data.occurrence) }
        // Ids copied from the series or already in the row stay valid even if the person was deleted since.
        val event = requireParentEvent(ctx, data.eventId)
        requireAddedPersons(ctx, data.participantIds.orEmpty() - existing?.participantIds.orEmpty() - event.participantIds)
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: EventOccurrenceData,
        seq: Long,
    ) {
        EventOccurrenceTable.insert {
            it[EventOccurrenceTable.id] = id
            it[householdId] = ctx.householdId
            CalendarRepository.writeOccurrence(it, data)
            it[EventOccurrenceTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
        notify(ctx, null, data)
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: EventOccurrenceData,
        seq: Long,
    ) {
        val old = load(id)?.data
        EventOccurrenceTable.update({ (EventOccurrenceTable.householdId eq ctx.householdId) and (EventOccurrenceTable.id eq id) }) {
            CalendarRepository.writeOccurrence(it, data)
            it[EventOccurrenceTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        notify(ctx, old, data)
    }

    private fun notify(
        ctx: MutationContext,
        old: EventOccurrenceData?,
        data: EventOccurrenceData,
    ) {
        val event = requireParentEvent(ctx, data.eventId)
        notifyAfterCommit(ctx, notifier, UUID.fromString(data.eventId)) { members, now, zone ->
            EventChanges.onOccurrenceRow(data.eventId, event, old, data, members, now, zone)
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes); only `EventSyncHandler.tombstone` removes these rows. */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Occurrence rows are removed only with their event")
}
