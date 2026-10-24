package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.TaskData
import app.lares.server.household.MemberRepository
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.MutationContext
import app.lares.server.tags.TagTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
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

/** `VALIDATION_FAILED` with one field error. */
internal fun invalidField(
    field: String,
    code: FieldErrorCode = FieldErrorCode.INVALID_FORMAT,
) = ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: $field", mapOf(field to code))

/** Ids not yet stored must be non-deleted members of the household; stored ones stay valid (clients hide unknown ids). */
internal fun requireAddedMembers(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw invalidField("assigneeIds")
    if (MemberRepository.activeIds(ctx.householdId, ids).size != ids.size) throw invalidField("assigneeIds")
}

/** Ids not yet stored must be non-deleted tags of the household (archived tags count). */
internal fun requireAddedTags(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw invalidField("tagIds")
    val found =
        TagTable
            .select(TagTable.id)
            .where { (TagTable.householdId eq ctx.householdId) and (TagTable.id inList ids) and (TagTable.deleted eq false) }
            .count()
    if (found != ids.size.toLong()) throw invalidField("tagIds")
}

private fun parseAll(ids: Set<String>): Set<UUID>? =
    ids.mapTo(mutableSetOf()) { runCatching { UUID.fromString(it) }.getOrNull() ?: return null }
