package app.lares.server.calendar

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.EventData
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.sync.recordChange
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireAddedPersons
import app.lares.server.sync.requireAddedTags
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Events and memorable dates (CAL-001…CAL-005, CAL-013, CAL-015). Entity-level rights come from `EventRules`; deleting an
 * event also tombstones its occurrence and reminder rows (ADR-0022). A rule this version cannot read decodes to `null`
 * and fails the strict round trip of `WritableSyncHandler` with `recurrence: INVALID_FORMAT`. Writes tell participants
 * through [EventNotifier] (CAL-007).
 */
class EventSyncHandler(
    private val notifier: EventNotifier,
) : WritableSyncHandler<EventData>(EventData.serializer()) {
    override val type: String = EntityTypes.EVENT

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        EventTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[EventTable.deleted]
            ChangeRow(
                type,
                row[EventTable.id].toString(),
                row[EventTable.seq],
                deleted,
                if (deleted) null else toJson(CalendarRepository.toEventData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.CALENDAR_VIEW)

    override fun load(id: UUID): StoredEntity<EventData>? = CalendarRepository.load(id)

    override fun normalize(data: EventData): EventData = Validators.normalizeEvent(data)

    override fun validate(
        ctx: MutationContext,
        data: EventData,
    ): Map<String, FieldErrorCode> = Validators.event(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: EventData?,
        merged: EventData?,
    ) {
        // Edits are checked against the stored event: an edit that takes the editor's own rights away is still allowed.
        val allowed =
            when {
                existing != null -> EventRules.canEdit(ctx.viewer, existing)
                merged != null -> EventRules.canCreate(ctx.viewer, merged)
                else -> false
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this event")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: EventData?,
        data: EventData,
    ) {
        if (existing != null) {
            if (existing.createdBy != data.createdBy) throw invalidField("createdBy")
            if (existing.kind != data.kind) throw invalidField("kind")
            if (existing.splitFrom != data.splitFrom) throw invalidField("splitFrom")
        } else {
            val origin = data.splitFrom
            if (origin != null) {
                // The origin may have been deleted since; it only has to be an event of this household.
                val originId = runCatching { UUID.fromString(origin) }.getOrNull()
                if (originId == null || originId == id || !CalendarRepository.existsInHousehold(ctx.householdId, originId)) {
                    throw invalidField("splitFrom")
                }
            }
        }
        requireAddedPersons(ctx, data.participantIds - existing?.participantIds.orEmpty())
        requireAddedTags(ctx, data.tagIds - existing?.tagIds.orEmpty())
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: EventData,
        seq: Long,
    ) {
        EventTable.insert {
            it[EventTable.id] = id
            it[householdId] = ctx.householdId
            CalendarRepository.writeEvent(it, data)
            it[EventTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
        // checkUpsert made sure splitFrom is an event of this household.
        val origin = data.splitFrom?.let { CalendarRepository.get(ctx.householdId, UUID.fromString(it))?.data }
        notifyAfterCommit(ctx, notifier, id) { members, now, zone ->
            EventChanges.onCreate(id.toString(), data, origin, members, now, zone)
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: EventData,
        seq: Long,
    ) {
        val old = CalendarRepository.get(ctx.householdId, id)?.data
        EventTable.update({ (EventTable.householdId eq ctx.householdId) and (EventTable.id eq id) }) {
            CalendarRepository.writeEvent(it, data)
            it[EventTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        if (old == null) return
        val rows = CalendarRepository.liveOccurrenceRows(id)
        notifyAfterCommit(ctx, notifier, id) { members, now, zone ->
            val hasLiveSplit = { CalendarRepository.liveSplitOf(ctx.householdId, id) != null }
            EventChanges.onUpdate(id.toString(), old, data, rows, hasLiveSplit, members, now, zone)
        }
    }

    /** Tombstones the event and then each live child, every child row with its own seq (rows must not share a seq). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        // What it was before the tombstones: the notice names its next occurrence.
        val stored = CalendarRepository.get(ctx.householdId, id)?.data
        val rows = CalendarRepository.liveOccurrenceRows(id)
        if (stored != null) {
            notifyAfterCommit(ctx, notifier, id) { members, now, zone ->
                EventChanges.onDelete(id.toString(), stored, rows, members, now, zone)
            }
        }
        EventTable.update({ (EventTable.householdId eq ctx.householdId) and (EventTable.id eq id) }) {
            it[deleted] = true
            it[EventTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        val tx = TransactionManager.current()
        for (rowId in CalendarRepository.liveOccurrenceIds(id)) {
            val rowSeq = tx.recordChange(ctx.householdId)
            EventOccurrenceTable.update({ EventOccurrenceTable.id eq rowId }) {
                it[deleted] = true
                it[EventOccurrenceTable.seq] = rowSeq
                it[updatedAt] = ctx.now
            }
        }
        for (rowId in CalendarRepository.liveReminderIds(id)) {
            val rowSeq = tx.recordChange(ctx.householdId)
            EventReminderTable.update({ EventReminderTable.id eq rowId }) {
                it[deleted] = true
                it[EventReminderTable.seq] = rowSeq
                it[updatedAt] = ctx.now
            }
        }
    }
}
