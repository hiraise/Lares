package app.lares.contract.permissions

import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData

/**
 * Entity-level write rules of the tasks module (tasks spec § Permissions and privacy, ADR-0014 § 6). Called by the
 * server's sync handlers and by the client's adapters; [WriteRules.canMutate] is only the coarse `tasks.view` gate.
 */
object TaskRules {
    /** Checklist item fields that ticking changes; changing only these needs [canComplete], anything else [canEdit]. */
    val CHECKLIST_TICK_FIELDS: Set<String> = setOf("done", "doneOccurrence")

    fun canCreate(
        viewer: Viewer,
        task: TaskData,
    ): Boolean = viewer.can(Permission.TASKS_CREATE) && task.createdBy == viewer.memberId

    /** Edit or delete the task, add, rename, reorder or delete its checklist items: the creator or `tasks.edit_any`. */
    fun canEdit(
        viewer: Viewer,
        task: TaskData,
    ): Boolean = viewer.sees() && (task.createdBy == viewer.memberId || viewer.permissions.has(Permission.TASKS_EDIT_ANY))

    /** Complete, skip, tick checklist items: assignees, everybody for "anyone" tasks, and whoever may edit (Q-017). */
    fun canComplete(
        viewer: Viewer,
        task: TaskData,
    ): Boolean = viewer.sees() && (task.assigneeIds.isEmpty() || viewer.memberId in task.assigneeIds || canEdit(viewer, task))

    /** Undo or reopen: whoever completed it, or whoever may edit the task. */
    fun canDeleteCompletion(
        viewer: Viewer,
        task: TaskData,
        completion: TaskCompletionData,
    ): Boolean = viewer.sees() && (completion.completedBy == viewer.memberId || canEdit(viewer, task))

    /** An update of a checklist item that changes [changedFields] (top-level field names). */
    fun canChangeChecklistItem(
        viewer: Viewer,
        task: TaskData,
        changedFields: Set<String>,
    ): Boolean = if (CHECKLIST_TICK_FIELDS.containsAll(changedFields)) canComplete(viewer, task) else canEdit(viewer, task)

    private fun Viewer.sees(): Boolean = permissions.has(Permission.TASKS_VIEW)

    private fun Viewer.can(permission: Permission): Boolean = sees() && permissions.has(permission)
}
