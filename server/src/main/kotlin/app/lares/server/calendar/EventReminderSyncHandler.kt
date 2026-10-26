package app.lares.server.calendar

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.EventReminderData
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
import app.lares.server.sync.requireDerivedId
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * A member's own reminder for an event (CAL-006, ADR-0023). Only that member writes it and only that member pulls it;
 * the row id is derived from event and member. Clients never delete these rows (`WriteRules` rejects it): "no reminder
 * for me" is `minutesBefore = null`; only the event's cascade tombstones them.
 */
class EventReminderSyncHandler : WritableSyncHandler<EventReminderData>(EventReminderData.serializer()) {
    override val type: String = EntityTypes.EVENT_REMINDER

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        EventReminderTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[EventReminderTable.deleted]
            ChangeRow(
                type,
                row[EventReminderTable.id].toString(),
                row[EventReminderTable.seq],
                deleted,
                if (deleted) null else toJson(CalendarRepository.toReminderData(row)),
            )
        }

    /** Live rows only for their member; a tombstone carries no data and is visible to everybody with the calendar. */
    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean {
        if (!viewer.permissions.has(Permission.CALENDAR_VIEW)) return false
        val data = row.data ?: return true
        return data["memberId"]?.jsonPrimitive?.content == viewer.memberId
    }

    override fun load(id: UUID): StoredEntity<EventReminderData>? =
        EventReminderTable.selectAll().where { EventReminderTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[EventReminderTable.householdId], it[EventReminderTable.deleted], CalendarRepository.toReminderData(it))
        }

    override fun validate(
        ctx: MutationContext,
        data: EventReminderData,
    ): Map<String, FieldErrorCode> = Validators.eventReminder(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: EventReminderData?,
        merged: EventReminderData?,
    ) {
        requireParentEvent(ctx, (existing ?: merged ?: return).eventId)
        // Both the stored row and the result must be the writer's own.
        val allowed =
            (existing == null || EventRules.canSetReminder(ctx.viewer, existing)) &&
                (merged == null || EventRules.canSetReminder(ctx.viewer, merged))
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this reminder")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: EventReminderData?,
        data: EventReminderData,
    ) {
        if (existing != null) {
            if (existing.eventId != data.eventId) throw invalidField("eventId")
            if (existing.memberId != data.memberId) throw invalidField("memberId")
        }
        requireDerivedId(id) { Ids.reminderRowId(data.eventId, data.memberId) }
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: EventReminderData,
        seq: Long,
    ) {
        EventReminderTable.insert {
            it[EventReminderTable.id] = id
            it[householdId] = ctx.householdId
            CalendarRepository.writeReminder(it, data)
            it[EventReminderTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: EventReminderData,
        seq: Long,
    ) {
        EventReminderTable.update({ (EventReminderTable.householdId eq ctx.householdId) and (EventReminderTable.id eq id) }) {
            CalendarRepository.writeReminder(it, data)
            it[EventReminderTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes); only `EventSyncHandler.tombstone` removes these rows. */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Reminder rows are removed only with their event")
}
