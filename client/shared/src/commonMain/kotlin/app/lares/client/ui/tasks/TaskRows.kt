package app.lares.client.ui.tasks

import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskView
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/** Builds list rows from task views; shared by the Tasks tab, the Today block and the tag page. */
class TaskRows(
    private val inputs: TaskInputs,
) {
    private val viewer: Viewer? = inputs.viewer
    private val today = inputs.now.toLocalDateTime(inputs.zone).date
    private val names = inputs.members.associate { it.id to it.name }

    /** Names of all members, for two-letter initials. */
    val allNames: List<String> = inputs.members.map { it.name }

    fun row(view: TaskView): TaskRowUi {
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
            it.data.taskId == view.id && !it.data.skipped && TaskRules.canDeleteCompletion(viewer, view.data, it.data)
        }
    }
}

/** Ticking rows off and undoing it, with one-shot [events] for snackbars. */
class TaskTicker(
    private val scope: CoroutineScope,
    private val writer: TasksWriter,
) {
    private val channel = Channel<TaskEvent>(Channel.BUFFERED)

    val events: Flow<TaskEvent> = channel.receiveAsFlow()

    /** Ticks an open row; unticks a done row (undo of today's completion, or reopen of a done one-off). */
    fun toggle(row: TaskRowUi) {
        if (!row.canToggle) {
            channel.trySend(TaskEvent.NotAllowed)
            return
        }
        val view = row.view
        scope.launch {
            val result =
                attempt {
                    val today = view.completedToday
                    when {
                        !row.done -> channel.send(TaskEvent.Completed(writer.complete(view.id)))
                        view.data.recurrence != null && today != null -> writer.undo(today.id)
                        else -> writer.reopen(view.id)
                    }
                }
            result.exceptionOrNull()?.let { channel.send(TaskEvent.Failed(it.toUiError())) }
        }
    }

    fun undo(completionId: String) {
        scope.launch {
            attempt { writer.undo(completionId) }.exceptionOrNull()?.let { channel.send(TaskEvent.Failed(it.toUiError())) }
        }
    }
}
