package app.lares.client.data.tasks

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.tasks.TaskGroup
import app.lares.contract.tasks.TaskSchedule
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Everything the task views are computed from; [viewer] is null when nobody is logged in. */
data class TaskInputs(
    val tasks: List<Pair<String, TaskData>>,
    val checklist: List<Pair<String, ChecklistItemData>>,
    val completions: List<TaskCompletion>,
    val members: List<MemberView>,
    val tags: List<Tag>,
    val zone: TimeZone,
    val viewer: Viewer?,
    val now: Instant,
)

/** Pure list logic of the tasks module (plan M1-04, TSK-020, TSK-021); no database, no clock. */
object TaskViews {
    /** Views of all tasks, ordered by id (creation order). Children of tasks not in [TaskInputs.tasks] are ignored. */
    fun build(inputs: TaskInputs): List<TaskView> {
        val completionsByTask = inputs.completions.groupBy { it.data.taskId }
        val checklistByTask = inputs.checklist.groupBy { it.second.taskId }
        val today = inputs.now.toLocalDateTime(inputs.zone).date
        return inputs.tasks.sortedBy { it.first }.map { (id, data) ->
            val completions = completionsByTask[id].orEmpty()
            val state = TaskSchedule.state(data, completions.map { it.data }, today, inputs.zone)
            val items = checklistByTask[id].orEmpty()
            TaskView(
                id = id,
                data = data,
                state = state,
                group = TaskSchedule.group(data, state, inputs.now, inputs.zone),
                overdue = TaskSchedule.isOverdue(data, state, inputs.now, inputs.zone),
                dueMoment = state.occurrence?.let { TaskSchedule.dueMoment(data, it, inputs.zone) },
                assignees = inputs.members.filter { it.id in data.assigneeIds },
                tags = inputs.tags.filter { it.id in data.tagIds },
                checklistDone = items.count { TaskSchedule.isChecklistItemDone(data, it.second, state) },
                checklistTotal = items.size,
                canEdit = inputs.viewer?.let { TaskRules.canEdit(it, data) } ?: false,
                canComplete = inputs.viewer?.let { TaskRules.canComplete(it, data) } ?: false,
                completedToday =
                    completions
                        .filter {
                            !it.data.skipped && it.data.completedAt
                                .toLocalDateTime(inputs.zone)
                                .date == today
                        }.maxWithOrNull(compareBy({ it.data.completedAt }, { it.id })),
            )
        }
    }

    /** The task page of [id], or null if the task is not in [TaskInputs.tasks]. */
    fun detail(
        inputs: TaskInputs,
        id: String,
    ): TaskDetail? {
        val view = build(inputs.copy(tasks = inputs.tasks.filter { it.first == id })).firstOrNull() ?: return null
        val checklist =
            inputs.checklist
                .filter { it.second.taskId == id }
                .sortedWith(compareBy({ it.second.position }, { it.first }))
                .map { (itemId, item) -> ChecklistItem(itemId, item, TaskSchedule.isChecklistItemDone(view.data, item, view.state)) }
        val history =
            inputs.completions
                .filter { it.data.taskId == id }
                .sortedWith(compareByDescending<TaskCompletion> { it.data.completedAt }.thenByDescending { it.id })
        return TaskDetail(view, checklist, history, inputs.members.firstOrNull { it.id == view.data.createdBy })
    }

    /**
     * The rows of one list. `showDone = false`: open tasks plus "done today" rows ([TaskView.isDoneTodayRow]);
     * `showDone = true`: done one-offs, latest completion first. [me] is the viewer's member id.
     */
    fun filter(
        views: List<TaskView>,
        filter: TaskFilter,
        me: String?,
    ): List<TaskView> {
        val query = filter.query.trim().lowercase()
        val matching =
            views.filter { view ->
                inScope(view.data, filter.scope, me) &&
                    (filter.assigneeId == null || filter.assigneeId in view.data.assigneeIds) &&
                    (filter.tagId == null || filter.tagId in view.data.tagIds) &&
                    (
                        query.isEmpty() ||
                            view.data.title
                                .lowercase()
                                .contains(query)
                    )
            }
        if (filter.showDone) {
            return matching
                .filter { it.group == TaskGroup.DONE }
                .sortedWith(compareByDescending<TaskView> { it.state.doneBy?.completedAt }.thenBy { it.id })
        }
        return matching.flatMap { view ->
            val doneToday = view.completedToday
            when {
                view.group == TaskGroup.DONE -> if (doneToday != null) listOf(view.copy(group = TaskGroup.TODAY)) else emptyList()

                // A routine completed today: its open row (next occurrence) and a ticked row for today's completion.
                doneToday != null -> listOf(view.copy(completedToday = null), view.copy(group = TaskGroup.TODAY, overdue = false))

                else -> listOf(view)
            }
        }
    }

    /** Non-empty sections in [TaskGroup] order, each sorted by the rules of plan M1-04. */
    fun sections(views: List<TaskView>): List<TaskSection> =
        TaskGroup.entries.mapNotNull { group ->
            val tasks = views.filter { it.group == group }
            if (tasks.isEmpty()) null else TaskSection(group, tasks.sortedWith(comparator(group)))
        }

    /** Open tasks per scope, for the chips; tag, assignee and search filters do not apply. */
    fun counts(
        views: List<TaskView>,
        me: String?,
    ): Map<TaskScope, Int> {
        val open = views.filter { it.group != TaskGroup.DONE }
        return TaskScope.entries.associateWith { scope -> open.count { inScope(it.data, scope, me) } }
    }

    private fun inScope(
        task: TaskData,
        scope: TaskScope,
        me: String?,
    ): Boolean =
        when (scope) {
            TaskScope.ALL -> true
            TaskScope.MINE -> task.assigneeIds.isEmpty() || me in task.assigneeIds
            TaskScope.ASSIGNED_BY_ME -> task.createdBy == me && task.assigneeIds.isNotEmpty() && task.assigneeIds != setOf(me)
        }

    private fun comparator(group: TaskGroup): Comparator<TaskView> =
        when (group) {
            TaskGroup.OVERDUE, TaskGroup.UPCOMING -> {
                compareBy<TaskView>({ it.state.occurrence }, { it.data.dueTime == null }, { it.data.dueTime }, { it.id })
            }

            TaskGroup.TODAY -> {
                compareBy<TaskView>(
                    { it.isDoneTodayRow },
                    { it.completedToday?.data?.completedAt },
                    { it.data.dueTime == null },
                    { it.data.dueTime },
                    { it.id },
                )
            }

            TaskGroup.NO_DATE -> {
                compareBy { it.id }
            }

            TaskGroup.DONE -> {
                compareByDescending<TaskView> { it.state.doneBy?.completedAt }.thenBy { it.id }
            }
        }
}
