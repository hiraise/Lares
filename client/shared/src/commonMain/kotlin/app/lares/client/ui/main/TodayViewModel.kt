package app.lares.client.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.tasks.TaskFilter
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskScope
import app.lares.client.data.tasks.TaskViews
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.ui.tasks.TaskEvent
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.tasks.TaskRows
import app.lares.client.ui.tasks.TaskTicker
import app.lares.contract.permissions.Permission
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The "Today" tasks block (TSK-023, FND-063). */
data class TodayUiState(
    val loaded: Boolean = false,
    /** The member sees tasks (`tasks.view`); otherwise the block is hidden. */
    val showTasks: Boolean = false,
    val canCreate: Boolean = false,
    /** Open overdue and today's tasks of the member (done rows not counted). */
    val count: Int = 0,
    /** Overdue, then today, then done today; at most [TodayViewModel.MAX_ROWS]. */
    val rows: List<TaskRowUi> = emptyList(),
    /** Rows left out of [rows]. */
    val more: Int = 0,
    val allNames: List<String> = emptyList(),
)

/** The member's overdue and today's tasks for the Today screen, ticked off right there. */
class TodayViewModel(
    repository: TasksRepository,
    writer: TasksWriter,
) : ViewModel() {
    private val ticker = TaskTicker(viewModelScope, writer)

    val events: Flow<TaskEvent> = ticker.events

    val state: StateFlow<TodayUiState> =
        repository
            .observeInputs()
            .map(::build)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TodayUiState())

    fun toggle(row: TaskRowUi) = ticker.toggle(row)

    fun undo(completionId: String) = ticker.undo(completionId)

    companion object {
        const val MAX_ROWS = 6

        fun build(inputs: TaskInputs): TodayUiState {
            val viewer = inputs.viewer
            if (viewer == null || !viewer.permissions.has(Permission.TASKS_VIEW)) return TodayUiState(loaded = true)
            val mine = TaskViews.filter(TaskViews.build(inputs), TaskFilter(scope = TaskScope.MINE), viewer.memberId)
            val views =
                TaskViews
                    .sections(mine)
                    .filter { it.group == TaskGroup.OVERDUE || it.group == TaskGroup.TODAY }
                    .flatMap { it.tasks }
            val rows = TaskRows(inputs)
            return TodayUiState(
                loaded = true,
                showTasks = true,
                canCreate = viewer.permissions.has(Permission.TASKS_CREATE),
                count = views.count { !it.isDoneTodayRow },
                rows = views.take(MAX_ROWS).map(rows::row),
                more = (views.size - MAX_ROWS).coerceAtLeast(0),
                allNames = rows.allNames,
            )
        }
    }
}
