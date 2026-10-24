package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.sync.recordChange
import app.lares.server.platform.toContract
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireAddedTags
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Tasks and routines (TSK-001…TSK-011). Entity-level rights come from `TaskRules`; deleting a task also tombstones its
 * checklist items and completions. A rule this version cannot read (unknown mode or unit) decodes to `null` and fails the
 * strict round trip of `WritableSyncHandler` with `recurrence: INVALID_FORMAT`. Every write refreshes the overdue check
 * time; added assignees are told after commit (TSK-031).
 */
class TaskSyncHandler(
    private val notifier: TaskNotifier,
) : WritableSyncHandler<TaskData>(TaskData.serializer()) {
    override val type: String = EntityTypes.TASK

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        TaskTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[TaskTable.deleted]
            ChangeRow(
                type,
                row[TaskTable.id].toString(),
                row[TaskTable.seq],
                deleted,
                if (deleted) null else toJson(TaskRepository.toTaskData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.TASKS_VIEW)

    override fun load(id: UUID): StoredEntity<TaskData>? = TaskRepository.load(id)

    override fun normalize(data: TaskData): TaskData = Validators.normalizeTask(data)

    override fun validate(
        ctx: MutationContext,
        data: TaskData,
    ): Map<String, FieldErrorCode> = Validators.task(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: TaskData?,
        merged: TaskData?,
    ) {
        // Edits are checked against the stored task: an edit that takes the editor's own rights away is still allowed.
        val allowed =
            when {
                existing != null -> TaskRules.canEdit(ctx.viewer, existing)
                merged != null -> TaskRules.canCreate(ctx.viewer, merged)
                else -> false
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this task")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: TaskData?,
        data: TaskData,
    ) {
        if (existing != null) {
            if (existing.createdBy != data.createdBy) throw invalidField("createdBy")
            // ADR-0017: a routine never becomes a one-off (its completions would lose their meaning).
            if (existing.recurrence != null && data.recurrence == null) throw invalidField("recurrence")
        }
        requireAddedMembers(ctx, data.assigneeIds - existing?.assigneeIds.orEmpty())
        requireAddedTags(ctx, data.tagIds - existing?.tagIds.orEmpty())
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: TaskData,
        seq: Long,
    ) {
        TaskTable.insert {
            it[TaskTable.id] = id
            it[householdId] = ctx.householdId
            TaskRepository.writeTask(it, data)
            it[TaskTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
        TaskOverdueTracker.refresh(ctx.householdId, id, ctx.now.toContract())
        notifyAssigned(ctx, id, data.assigneeIds)
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: TaskData,
        seq: Long,
    ) {
        val before =
            TaskRepository
                .get(ctx.householdId, id)
                ?.data
                ?.assigneeIds
                .orEmpty()
        TaskTable.update({ (TaskTable.householdId eq ctx.householdId) and (TaskTable.id eq id) }) {
            TaskRepository.writeTask(it, data)
            it[TaskTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        TaskOverdueTracker.refresh(ctx.householdId, id, ctx.now.toContract())
        notifyAssigned(ctx, id, data.assigneeIds - before)
    }

    /** `task.assigned` to members added by this write, except the writer; nothing for removals or "anyone" tasks. */
    private fun notifyAssigned(
        ctx: MutationContext,
        id: UUID,
        added: Set<String>,
    ) {
        val actor = UUID.fromString(ctx.viewer.memberId)
        // Added ids were checked by checkUpsert; the stored ones may be anything a client kept.
        val members = added.mapNotNullTo(mutableSetOf()) { runCatching { UUID.fromString(it) }.getOrNull() } - actor
        if (members.isEmpty()) return
        TransactionManager.current().afterCommit {
            notifier.enqueue(TaskNotifier.Assigned(ctx.householdId, id, actor, members))
        }
    }

    /** Tombstones the task and then each live child, every child row with its own seq (rows must not share a seq). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        TaskTable.update({ (TaskTable.householdId eq ctx.householdId) and (TaskTable.id eq id) }) {
            it[deleted] = true
            it[TaskTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        val tx = TransactionManager.current()
        val items =
            ChecklistItemTable
                .select(ChecklistItemTable.id)
                .where { (ChecklistItemTable.taskId eq id) and (ChecklistItemTable.deleted eq false) }
                .orderBy(ChecklistItemTable.id)
                .map { it[ChecklistItemTable.id] }
        for (itemId in items) {
            val itemSeq = tx.recordChange(ctx.householdId)
            ChecklistItemTable.update({ ChecklistItemTable.id eq itemId }) {
                it[deleted] = true
                it[ChecklistItemTable.seq] = itemSeq
                it[updatedAt] = ctx.now
            }
        }
        val completions =
            TaskCompletionTable
                .select(TaskCompletionTable.id)
                .where { (TaskCompletionTable.taskId eq id) and (TaskCompletionTable.deleted eq false) }
                .orderBy(TaskCompletionTable.id)
                .map { it[TaskCompletionTable.id] }
        for (completionId in completions) {
            val completionSeq = tx.recordChange(ctx.householdId)
            TaskCompletionTable.update({ TaskCompletionTable.id eq completionId }) {
                it[deleted] = true
                it[TaskCompletionTable.seq] = completionSeq
                it[updatedAt] = ctx.now
            }
        }
        TaskOverdueTracker.refresh(ctx.householdId, id, ctx.now.toContract())
    }
}
