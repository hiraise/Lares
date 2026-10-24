package app.lares.client.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.tasks.ChecklistItem
import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskDetail
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskViews
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.permissions.TaskRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Parameters of [TaskDetailViewModel] (Koin `parametersOf`). */
data class TaskDetailArgs(
    val id: String,
)

/** A history entry; only the latest one can be undone, by its author or whoever may edit the task. */
data class HistoryEntry(
    val completion: TaskCompletion,
    val canUndo: Boolean,
)

data class TaskDetailUiState(
    val loaded: Boolean = false,
    val detail: TaskDetail? = null,
    val today: LocalDate = LocalDate(1970, 1, 1),
    val zone: TimeZone = TimeZone.UTC,
    val names: Map<String, String> = emptyMap(),
    /** A done one-off the member may mark as not done. */
    val canReopen: Boolean = false,
    /** The last [HISTORY_SIZE] completions and skips, newest first (routines only). */
    val history: List<HistoryEntry> = emptyList(),
) {
    /** Loaded, but the task does not exist (deleted here or on another phone): the page closes. */
    val gone: Boolean get() = loaded && detail == null

    companion object {
        const val HISTORY_SIZE = 20
    }
}

/** The task page (plan M1-05, TSK-024): completion, checklist, history, skip, delete. */
class TaskDetailViewModel(
    repository: TasksRepository,
    private val writer: TasksWriter,
    args: TaskDetailArgs,
) : ViewModel() {
    private val id = args.id
    private val eventChannel = Channel<TaskEvent>(Channel.BUFFERED)

    val events: Flow<TaskEvent> = eventChannel.receiveAsFlow()

    /** The inline "Add item" field: null = closed. */
    private val mutableNewItem = MutableStateFlow<String?>(null)
    val newItem: StateFlow<String?> = mutableNewItem

    val state: StateFlow<TaskDetailUiState> =
        repository
            .observeInputs()
            .map(::build)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TaskDetailUiState())

    fun complete() = write { eventChannel.send(TaskEvent.Completed(writer.complete(id))) }

    fun reopen() = write { writer.reopen(id) }

    fun skip() =
        write {
            writer.skip(id)
            eventChannel.send(TaskEvent.Skipped)
        }

    /** Deletes the task (a routine: the whole series); the page closes when the task disappears. */
    fun delete() = write { writer.delete(id) }

    fun undo(completionId: String) = write { writer.undo(completionId) }

    fun toggleItem(item: ChecklistItem) {
        if (state.value.detail
                ?.task
                ?.canComplete != true
        ) {
            eventChannel.trySend(TaskEvent.NotAllowed)
            return
        }
        write { writer.setChecklistItemDone(item.id, !item.shownDone) }
    }

    fun openNewItem() {
        mutableNewItem.value = ""
    }

    fun onNewItem(text: String) {
        mutableNewItem.value = text
    }

    fun closeNewItem() {
        mutableNewItem.value = null
    }

    /** Adds the typed item and keeps the field open for the next one. */
    fun addItem() {
        val text = mutableNewItem.value?.trim().orEmpty()
        if (text.isEmpty()) return
        mutableNewItem.value = ""
        write { writer.addChecklistItem(id, text) }
    }

    fun renameItem(
        itemId: String,
        text: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        write { writer.renameChecklistItem(itemId, trimmed) }
    }

    fun deleteItem(itemId: String) = write { writer.deleteChecklistItem(itemId) }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt { block() }.exceptionOrNull()?.let { eventChannel.send(TaskEvent.Failed(it.toUiError())) }
        }
    }

    private fun build(inputs: TaskInputs): TaskDetailUiState {
        val detail = TaskViews.detail(inputs, id)
        val viewer = inputs.viewer
        val base =
            TaskDetailUiState(
                loaded = true,
                detail = detail,
                today = inputs.now.toLocalDateTime(inputs.zone).date,
                zone = inputs.zone,
                names = inputs.members.associate { it.id to it.name },
            )
        if (detail == null || viewer == null) return base
        val task = detail.task.data
        val deletable = { c: TaskCompletion -> TaskRules.canDeleteCompletion(viewer, task, c.data) }
        val history =
            if (task.recurrence == null) {
                emptyList()
            } else {
                detail.history.take(TaskDetailUiState.HISTORY_SIZE).mapIndexed { index, c -> HistoryEntry(c, index == 0 && deletable(c)) }
            }
        return base.copy(
            canReopen = detail.task.state.done && detail.history.any { !it.data.skipped && deletable(it) },
            history = history,
        )
    }
}
