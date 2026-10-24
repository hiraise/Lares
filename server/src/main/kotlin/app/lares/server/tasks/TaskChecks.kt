package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.TaskData
import app.lares.server.household.MemberRepository
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.MutationContext
import app.lares.server.sync.invalidField
import app.lares.server.sync.parseAll
import java.util.UUID

// Server-only checks of the tasks handlers that need the database (architecture/tasks.md § Validation).

/** The parent task of a checklist item or completion: `ENTITY_NOT_FOUND` if missing or elsewhere, `ENTITY_DELETED` if tombstoned. */
internal fun requireParentTask(
    ctx: MutationContext,
    taskId: String,
): TaskData {
    val id = runCatching { UUID.fromString(taskId) }.getOrNull() ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Task not found")
    val task = TaskRepository.get(ctx.householdId, id) ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Task not found")
    if (task.deleted) throw ApiException(ErrorCode.ENTITY_DELETED, "Task was deleted")
    return task.data
}

/** Ids not yet stored must be non-deleted members of the household; stored ones stay valid (clients hide unknown ids). */
internal fun requireAddedMembers(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw invalidField("assigneeIds")
    if (MemberRepository.activeIds(ctx.householdId, ids).size != ids.size) throw invalidField("assigneeIds")
}
