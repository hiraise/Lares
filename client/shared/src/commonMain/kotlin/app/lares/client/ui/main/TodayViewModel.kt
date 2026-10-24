package app.lares.client.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.MemberView
import app.lares.client.data.shopping.ActiveList
import app.lares.client.data.shopping.ShoppingRepository
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** The active shopping list as the Today hero card (SHP-013, SHP-012). */
data class TodayShoppingUi(
    val listId: String,
    val name: String,
    val bought: Int,
    /** Bought + to buy. */
    val total: Int,
    /** The first names to buy, in display order. */
    val remaining: List<String>,
    /** Items to buy left out of [remaining]. */
    val more: Int,
    /** Another member ticked something in this list during the last 15 minutes. */
    val shopper: MemberView?,
    /** Other lists with items to buy: «Ещё 2 списка». */
    val othersToBuy: Int,
) {
    val fraction: Float get() = if (total == 0) 0f else bought.toFloat() / total
}

/** The "Today" blocks (FND-063): the active shopping list and the tasks (TSK-023). */
data class TodayUiState(
    val loaded: Boolean = false,
    /** Null when the member does not see Shopping or no list has items to buy. */
    val shopping: TodayShoppingUi? = null,
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
) {
    /** Nothing to show: the empty state. */
    val empty: Boolean get() = loaded && shopping == null && !showTasks
}

/** The active shopping list and the member's overdue and today's tasks (ticked off right there) for the Today screen. */
class TodayViewModel(
    repository: TasksRepository,
    writer: TasksWriter,
    shopping: ShoppingRepository,
) : ViewModel() {
    private val ticker = TaskTicker(viewModelScope, writer)

    val events: Flow<TaskEvent> = ticker.events

    val state: StateFlow<TodayUiState> =
        combine(repository.observeInputs(), shopping.observeActive(), ::build)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TodayUiState())

    fun toggle(row: TaskRowUi) = ticker.toggle(row)

    fun undo(completionId: String) = ticker.undo(completionId)

    companion object {
        const val MAX_ROWS = 6

        fun build(
            inputs: TaskInputs,
            active: ActiveList? = null,
        ): TodayUiState {
            val viewer = inputs.viewer
            val shopping = active?.takeIf { viewer?.permissions?.has(Permission.SHOPPING_VIEW) == true }?.let(::shopping)
            if (viewer == null || !viewer.permissions.has(Permission.TASKS_VIEW)) return TodayUiState(loaded = true, shopping = shopping)
            val mine = TaskViews.filter(TaskViews.build(inputs), TaskFilter(scope = TaskScope.MINE), viewer.memberId)
            val views =
                TaskViews
                    .sections(mine)
                    .filter { it.group == TaskGroup.OVERDUE || it.group == TaskGroup.TODAY }
                    .flatMap { it.tasks }
            val rows = TaskRows(inputs)
            return TodayUiState(
                loaded = true,
                shopping = shopping,
                showTasks = true,
                canCreate = viewer.permissions.has(Permission.TASKS_CREATE),
                count = views.count { !it.isDoneTodayRow },
                rows = views.take(MAX_ROWS).map(rows::row),
                more = (views.size - MAX_ROWS).coerceAtLeast(0),
                allNames = rows.allNames,
            )
        }

        private fun shopping(active: ActiveList): TodayShoppingUi {
            val list = active.list
            return TodayShoppingUi(
                listId = list.id,
                name = list.data.name,
                bought = list.bought,
                total = list.needed + list.bought,
                remaining = list.remaining,
                more = (list.needed - list.remaining.size).coerceAtLeast(0),
                shopper = list.shopper,
                othersToBuy = active.othersToBuy,
            )
        }
    }
}
