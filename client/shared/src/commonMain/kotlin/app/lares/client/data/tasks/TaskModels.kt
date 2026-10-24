package app.lares.client.data.tasks

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.TaskGroup
import app.lares.contract.tasks.TaskState
import kotlin.time.Instant

// What the tasks repository exposes (plan M1-04): ready-to-show views computed from the synced rows.

/**
 * A task as the lists show it. [state], [group], [overdue] and [dueMoment] are computed for the current occurrence at
 * the current minute in the household time zone.
 *
 * A "done today" row ([TaskViews.filter] with `showDone = false`) has [group] `TODAY` and a non-null [completedToday];
 * for a routine completed today it appears next to the routine's open row (same [id]).
 */
data class TaskView(
    val id: String,
    val data: TaskData,
    val state: TaskState,
    val group: TaskGroup,
    val overdue: Boolean,
    val dueMoment: Instant?,
    /** Unknown or deleted member ids are dropped. */
    val assignees: List<MemberView>,
    /** Unknown or deleted tag ids are dropped; archived tags are kept. */
    val tags: List<Tag>,
    val checklistDone: Int,
    val checklistTotal: Int,
    val canEdit: Boolean,
    val canComplete: Boolean,
    /** The latest non-skipped completion made today (household zone), for "done today" rows. */
    val completedToday: TaskCompletion?,
) {
    /** True for a "done today" row (see the class comment). */
    val isDoneTodayRow: Boolean get() = group == TaskGroup.TODAY && completedToday != null
}

data class TaskCompletion(
    val id: String,
    val data: TaskCompletionData,
)

/** [shownDone]: routine checklists start unticked for every occurrence (TSK-004). */
data class ChecklistItem(
    val id: String,
    val data: ChecklistItemData,
    val shownDone: Boolean,
)

/** The task page: [checklist] in display order, [history] newest first (skips included). */
data class TaskDetail(
    val task: TaskView,
    val checklist: List<ChecklistItem>,
    val history: List<TaskCompletion>,
    val creator: MemberView?,
)

data class TaskFilter(
    val scope: TaskScope = TaskScope.ALL,
    val assigneeId: String? = null,
    val tagId: String? = null,
    val showDone: Boolean = false,
    val query: String = "",
)

enum class TaskScope { ALL, MINE, ASSIGNED_BY_ME }

/** One list section; [TaskViews.sections] returns only non-empty ones, in [TaskGroup] order. */
data class TaskSection(
    val group: TaskGroup,
    val tasks: List<TaskView>,
)
