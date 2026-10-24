package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.toContract
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Completions and skips (TSK-003, ADR-0017). Immutable: undo and reopen delete them. Only the member themselves can
 * complete (`completedBy` is the writer), and only if `canComplete`. Every write refreshes the task's overdue check
 * time; the first completion of an occurrence may tell the creator (TSK-032).
 */
class TaskCompletionSyncHandler(
    private val notifier: TaskNotifier,
) : WritableSyncHandler<TaskCompletionData>(TaskCompletionData.serializer()) {
    override val type: String = EntityTypes.TASK_COMPLETION

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        TaskCompletionTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[TaskCompletionTable.deleted]
            ChangeRow(
                type,
                row[TaskCompletionTable.id].toString(),
                row[TaskCompletionTable.seq],
                deleted,
                if (deleted) null else toJson(TaskRepository.toCompletionData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.TASKS_VIEW)

    override fun load(id: UUID): StoredEntity<TaskCompletionData>? =
        TaskCompletionTable.selectAll().where { TaskCompletionTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[TaskCompletionTable.householdId], it[TaskCompletionTable.deleted], TaskRepository.toCompletionData(it))
        }

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: TaskCompletionData?,
        merged: TaskCompletionData?,
    ) {
        when {
            existing == null && merged != null -> {
                val task = requireParentTask(ctx, merged.taskId)
                if (merged.completedBy != ctx.viewer.memberId) {
                    throw ApiException(ErrorCode.FORBIDDEN, "A completion is always written by its author")
                }
                if (!TaskRules.canComplete(ctx.viewer, task)) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to complete this task")
            }

            existing != null && merged != null -> {
                val before = toJson(existing)
                val after = toJson(merged)
                val changed = (before.keys + after.keys).filter { before[it] != after[it] }
                if (changed.isNotEmpty()) {
                    throw ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Completions cannot be changed",
                        changed.associateWith { FieldErrorCode.INVALID_FORMAT },
                    )
                }
            }

            existing != null -> {
                val task = requireParentTask(ctx, existing.taskId)
                if (!TaskRules.canDeleteCompletion(ctx.viewer, task, existing)) {
                    throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to undo this completion")
                }
            }
        }
    }

    override fun validate(
        ctx: MutationContext,
        data: TaskCompletionData,
    ): Map<String, FieldErrorCode> {
        // The parent exists and is live: authorize checked it.
        val task = TaskRepository.get(ctx.householdId, UUID.fromString(data.taskId))?.data ?: return emptyMap()
        val errors = Validators.taskCompletion(data, task).toMutableMap()
        if (data.completedAt > ctx.now.toContract() + 1.days) errors["completedAt"] = FieldErrorCode.OUT_OF_RANGE
        return errors
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: TaskCompletionData,
        seq: Long,
    ) {
        val taskId = UUID.fromString(data.taskId)
        val notifyCreator = shouldNotifyCreator(ctx, taskId, data)
        TaskCompletionTable.insert {
            it[TaskCompletionTable.id] = id
            it[householdId] = ctx.householdId
            TaskRepository.writeCompletion(it, data)
            it[TaskCompletionTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
        TaskOverdueTracker.refresh(ctx.householdId, taskId, ctx.now.toContract())
        if (notifyCreator) {
            val actor = UUID.fromString(data.completedBy)
            TransactionManager.current().afterCommit { notifier.enqueue(TaskNotifier.Completed(ctx.householdId, taskId, actor)) }
        }
    }

    /**
     * `task.completed` goes to a creator who opted in, when someone else completes (not skips) the occurrence and nobody
     * completed it before: a one-off has no earlier non-skipped completion, a routine none of the same occurrence.
     * Runs before the insert, under the household lock.
     */
    private fun shouldNotifyCreator(
        ctx: MutationContext,
        taskId: UUID,
        data: TaskCompletionData,
    ): Boolean {
        if (data.skipped) return false
        val task = TaskRepository.get(ctx.householdId, taskId)?.data ?: return false
        if (!task.notifyCreatorOnDone || data.completedBy == task.createdBy) return false
        return TaskRepository.completionsOf(ctx.householdId, taskId).none { earlier ->
            !earlier.data.skipped && (task.recurrence == null || earlier.data.occurrence == data.occurrence)
        }
    }

    /** Never reached: [authorize] rejects every change and an unchanged upsert is a no-op. */
    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: TaskCompletionData,
        seq: Long,
    ) = error("Task completions are immutable")

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        TaskCompletionTable.update({ (TaskCompletionTable.householdId eq ctx.householdId) and (TaskCompletionTable.id eq id) }) {
            it[deleted] = true
            it[TaskCompletionTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        val taskId =
            TaskCompletionTable
                .select(TaskCompletionTable.taskId)
                .where { TaskCompletionTable.id eq id }
                .single()[TaskCompletionTable.taskId]
        TaskOverdueTracker.refresh(ctx.householdId, taskId, ctx.now.toContract())
    }
}
