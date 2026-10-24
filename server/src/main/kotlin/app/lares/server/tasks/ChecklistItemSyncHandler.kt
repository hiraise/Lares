package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.TaskRules
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Checklist items (TSK-004). Ticking needs `canComplete` on the parent task; any other change needs `canEdit`. */
class ChecklistItemSyncHandler : WritableSyncHandler<ChecklistItemData>(ChecklistItemData.serializer()) {
    override val type: String = EntityTypes.CHECKLIST_ITEM

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        ChecklistItemTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[ChecklistItemTable.deleted]
            ChangeRow(
                type,
                row[ChecklistItemTable.id].toString(),
                row[ChecklistItemTable.seq],
                deleted,
                if (deleted) null else toJson(TaskRepository.toChecklistItemData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.TASKS_VIEW)

    override fun load(id: UUID): StoredEntity<ChecklistItemData>? =
        ChecklistItemTable.selectAll().where { ChecklistItemTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[ChecklistItemTable.householdId], it[ChecklistItemTable.deleted], TaskRepository.toChecklistItemData(it))
        }

    override fun normalize(data: ChecklistItemData): ChecklistItemData = Validators.normalizeChecklistItem(data)

    override fun validate(
        ctx: MutationContext,
        data: ChecklistItemData,
    ): Map<String, FieldErrorCode> = Validators.checklistItem(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: ChecklistItemData?,
        merged: ChecklistItemData?,
    ) {
        // The stored parent decides; a changed taskId is rejected by checkUpsert.
        val task = requireParentTask(ctx, (existing ?: merged ?: return).taskId)
        val allowed =
            if (existing != null && merged != null) {
                val before = toJson(existing)
                val after = toJson(merged)
                val changed = (before.keys + after.keys).filterTo(mutableSetOf()) { before[it] != after[it] }
                TaskRules.canChangeChecklistItem(ctx.viewer, task, changed)
            } else {
                TaskRules.canEdit(ctx.viewer, task)
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this checklist item")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: ChecklistItemData?,
        data: ChecklistItemData,
    ) {
        if (existing != null && existing.taskId != data.taskId) throw invalidField("taskId")
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: ChecklistItemData,
        seq: Long,
    ) {
        ChecklistItemTable.insert {
            it[ChecklistItemTable.id] = id
            it[householdId] = ctx.householdId
            TaskRepository.writeChecklistItem(it, data)
            it[ChecklistItemTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: ChecklistItemData,
        seq: Long,
    ) {
        ChecklistItemTable.update({ (ChecklistItemTable.householdId eq ctx.householdId) and (ChecklistItemTable.id eq id) }) {
            TaskRepository.writeChecklistItem(it, data)
            it[ChecklistItemTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        ChecklistItemTable.update({ (ChecklistItemTable.householdId eq ctx.householdId) and (ChecklistItemTable.id eq id) }) {
            it[deleted] = true
            it[ChecklistItemTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }
}
