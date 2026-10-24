package app.lares.client.platform

import app.lares.client.data.tasks.TasksRepository
import app.lares.client.sync.SyncEngine
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** A screen a notification tap asks for. */
sealed interface NavTarget {
    data class Task(
        val id: String,
    ) : NavTarget

    /** The tapped task was deleted or is no longer visible: the Tasks tab with a notice. */
    data object TaskGone : NavTarget
}

/**
 * The screen the app was asked to open by a notification tap (plan M1-07). The platform submits, the logged-in UI
 * consumes; a target submitted before login waits.
 */
class PendingNavigation {
    private val pending = MutableStateFlow<NavTarget?>(null)

    val target: StateFlow<NavTarget?> = pending.asStateFlow()

    /** Returns false for entity types without a screen yet. */
    fun submit(
        entityType: String,
        entityId: String,
    ): Boolean {
        val target =
            when (entityType) {
                EntityTypes.TASK -> NavTarget.Task(entityId)
                else -> return false
            }
        pending.value = target
        return true
    }

    fun submit(target: NavTarget) {
        pending.value = target
    }

    /** Takes [target] if it is still pending, so it is handled once. */
    fun consume(target: NavTarget): Boolean = pending.compareAndSet(target, null)
}

/** Whether a tapped task can be opened; a task that was just assigned may still be on its way, so it syncs once. */
class TaskLinks(
    private val tasks: TasksRepository,
    private val sync: SyncEngine,
) {
    suspend fun exists(id: String): Boolean = visible(id) || (sync.syncNow() && visible(id))

    private suspend fun visible(id: String) = tasks.observeViews().first().any { it.id == id }
}
