package app.lares.client.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.Group
import app.lares.client.data.GroupsRepository
import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskFilter
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskScope
import app.lares.client.data.tasks.TaskView
import app.lares.client.data.tasks.TaskViews
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.permissions.GroupView
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionEvaluator
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

data class TaskSectionUi(
    val group: TaskGroup,
    /** Open rows; "done today" rows are not counted. */
    val count: Int,
    val rows: List<TaskRowUi>,
)

data class TasksUiState(
    val loaded: Boolean = false,
    val filter: TaskFilter = TaskFilter(scope = TaskScope.MINE),
    val searchOpen: Boolean = false,
    val sections: List<TaskSectionUi> = emptyList(),
    /** Open tasks per scope for the chips. */
    val counts: Map<TaskScope, Int> = emptyMap(),
    /** Members with `tasks.view`, for the assignee filter. */
    val members: List<MemberView> = emptyList(),
    /** Active tags for the tag picker. */
    val tags: List<Tag> = emptyList(),
    /** The chosen tag (may be archived). */
    val selectedTag: Tag? = null,
    val canCreate: Boolean = false,
    /** Names of all members, for two-letter initials. */
    val allNames: List<String> = emptyList(),
) {
    /** Anything besides the scope narrows the list. */
    val filtered: Boolean
        get() = filter.assigneeId != null || filter.tagId != null || filter.showDone || filter.query.isNotBlank()
}

/** One-shot results the screen shows as snackbars. */
sealed interface TaskEvent {
    /** Completed; the snackbar offers undo of [completionId]. */
    data class Completed(
        val completionId: String,
    ) : TaskEvent

    data object Skipped : TaskEvent

    /** The member may not tick this task. */
    data object NotAllowed : TaskEvent

    data class Failed(
        val error: UiError,
    ) : TaskEvent
}

/**
 * The Tasks tab (plan M1-05, TSK-020, TSK-021): filter chips, sections, ticking off with undo. Scoped to the main graph
 * entry, so the filter survives tab switches.
 */
class TasksViewModel(
    repository: TasksRepository,
    private val writer: TasksWriter,
    groups: GroupsRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter(scope = TaskScope.MINE))
    private val searchOpen = MutableStateFlow(false)
    private val eventChannel = Channel<TaskEvent>(Channel.BUFFERED)

    val events: Flow<TaskEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<TasksUiState> =
        combine(repository.observeInputs(), groups.observeAll(), filter, searchOpen, ::build)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    fun setScope(scope: TaskScope) = filter.update { it.copy(scope = scope) }

    fun setTag(tagId: String?) = filter.update { it.copy(tagId = tagId) }

    fun setAssignee(memberId: String?) = filter.update { it.copy(assigneeId = memberId) }

    fun setShowDone(showDone: Boolean) = filter.update { it.copy(showDone = showDone) }

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun toggleSearch() {
        val open = !searchOpen.value
        searchOpen.value = open
        if (!open) setQuery("")
    }

    /** Back to the default: my open tasks, no search. */
    fun resetFilters() {
        filter.value = TaskFilter(scope = TaskScope.MINE)
        searchOpen.value = false
    }

    /** Ticks an open row; unticks a done row (undo of today's completion, or reopen of a done one-off). */
    fun toggle(row: TaskRowUi) {
        if (!row.canToggle) {
            eventChannel.trySend(TaskEvent.NotAllowed)
            return
        }
        val view = row.view
        viewModelScope.launch {
            val result =
                attempt {
                    val today = view.completedToday
                    when {
                        !row.done -> eventChannel.send(TaskEvent.Completed(writer.complete(view.id)))
                        view.data.recurrence != null && today != null -> writer.undo(today.id)
                        else -> writer.reopen(view.id)
                    }
                }
            result.exceptionOrNull()?.let { eventChannel.send(TaskEvent.Failed(it.toUiError())) }
        }
    }

    fun undo(completionId: String) {
        viewModelScope.launch {
            attempt { writer.undo(completionId) }.exceptionOrNull()?.let { eventChannel.send(TaskEvent.Failed(it.toUiError())) }
        }
    }

    private fun build(
        inputs: TaskInputs,
        groups: List<Group>,
        filter: TaskFilter,
        searchOpen: Boolean,
    ): TasksUiState {
        val viewer = inputs.viewer
        val me = viewer?.memberId
        val today = inputs.now.toLocalDateTime(inputs.zone).date
        val names = inputs.members.associate { it.id to it.name }
        val views = TaskViews.build(inputs)
        val rows = TaskViews.filter(views, filter, me)
        val toRow = { view: TaskView -> row(view, inputs, viewer, today, names) }
        val sections =
            if (filter.showDone) {
                if (rows.isEmpty()) emptyList() else listOf(TaskSectionUi(TaskGroup.DONE, rows.size, rows.map(toRow)))
            } else {
                TaskViews.sections(rows).map { section ->
                    TaskSectionUi(section.group, section.tasks.count { !it.isDoneTodayRow }, section.tasks.map(toRow))
                }
            }
        return TasksUiState(
            loaded = true,
            filter = filter,
            searchOpen = searchOpen,
            sections = sections,
            counts = TaskViews.counts(views, me),
            members = taskMembers(inputs.members, groups),
            tags = inputs.tags.filter { !it.data.archived }.sortedBy { it.data.name.lowercase() },
            selectedTag = inputs.tags.firstOrNull { it.id == filter.tagId },
            canCreate = viewer?.permissions?.has(Permission.TASKS_CREATE) == true,
            allNames = inputs.members.map { it.name },
        )
    }

    private fun row(
        view: TaskView,
        inputs: TaskInputs,
        viewer: Viewer?,
        today: LocalDate,
        names: Map<String, String>,
    ): TaskRowUi {
        val done = view.isDoneTodayRow || view.group == TaskGroup.DONE
        val canToggle =
            when {
                viewer == null -> false
                !done -> view.canComplete
                else -> undoable(view, inputs.completions, viewer)
            }
        val key = if (view.isDoneTodayRow && view.data.recurrence != null) "${view.id}-done" else view.id
        return TaskRowUi(key, view, TasksLogic.meta(view, today, inputs.zone, names), done, canToggle)
    }

    private fun undoable(
        view: TaskView,
        completions: List<TaskCompletion>,
        viewer: Viewer,
    ): Boolean {
        val today = view.completedToday
        if (view.data.recurrence != null && today != null) return TaskRules.canDeleteCompletion(viewer, view.data, today.data)
        return completions.any {
            it.data.taskId == view.id && !it.data.skipped &&
                TaskRules.canDeleteCompletion(
                    viewer,
                    view.data,
                    it.data,
                )
        }
    }

    companion object {
        /** Members who see tasks (the assignee filter); everybody before the groups have synced. */
        fun taskMembers(
            members: List<MemberView>,
            groups: List<Group>,
        ): List<MemberView> {
            if (groups.isEmpty()) return members
            val views = groups.map { GroupView(it.id, it.data.builtIn, it.data.permissions, it.data.memberIds, deleted = false) }
            return members.filter { PermissionEvaluator.evaluate(it.id, views).has(Permission.TASKS_VIEW) }
        }
    }
}
