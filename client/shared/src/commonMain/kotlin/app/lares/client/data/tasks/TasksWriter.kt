package app.lares.client.data.tasks

import app.lares.client.auth.LocalSession
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.toData
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.tasks.TaskSchedule
import app.lares.contract.tasks.TaskState
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every user action on tasks (plan M1-04), through [LocalWriter]: rights and validation are checked locally and the
 * change works offline. Throws [LocalWriteException] when the change is not allowed or invalid.
 */
class TasksWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
    private val clock: Clock,
) {
    /** Creates a task with [checklist] items (in that order) and returns its id; `createdBy` is set to the member. */
    suspend fun create(
        draft: TaskData,
        checklist: List<String> = emptyList(),
    ): String {
        val me = localDb.read { viewer() }
        val id = Ids.newId()
        writer.upsert(EntityTypes.TASK, id, encode(TaskData.serializer(), draft.copy(createdBy = me.memberId)))
        checklist.forEachIndexed { index, text -> upsertItem(Ids.newId(), ChecklistItemData(id, text, index)) }
        return id
    }

    /** Saves [newData] as the task; only top-level fields that differ from the local copy are sent. */
    suspend fun update(
        id: String,
        newData: TaskData,
    ) {
        val current = localDb.read { taskQueries.selectById(id).executeAsOneOrNull()?.toData() }
        val currentJson = current?.let { encode(TaskData.serializer(), it) } ?: JsonObject(emptyMap())
        val newJson = encode(TaskData.serializer(), newData)
        val keys = (currentJson.keys + newJson.keys).filter { currentJson[it] != newJson[it] }
        if (current != null && keys.isEmpty()) return
        writer.upsert(EntityTypes.TASK, id, JsonObject(keys.associateWith { newJson[it] ?: JsonNull }))
    }

    /** Deletes the task; the server's cascade removes its checklist and history. */
    suspend fun delete(id: String) = writer.delete(EntityTypes.TASK, id)

    /** Completes the current occurrence (even a future one) and returns the completion id for undo. */
    suspend fun complete(id: String): String = addCompletion(id, skipped = false)

    /** Skips the current occurrence of a routine. */
    suspend fun skip(id: String): String = addCompletion(id, skipped = true)

    suspend fun undo(completionId: String) = writer.delete(EntityTypes.TASK_COMPLETION, completionId)

    /** Marks a done one-off as not done: deletes every non-skipped completion the member may delete. */
    suspend fun reopen(taskId: String) {
        val deletable =
            localDb.read {
                val viewer = viewer()
                val task = task(taskId)
                task_completionQueries
                    .selectByTask(taskId)
                    .executeAsList()
                    .filter { it.skipped == 0L && TaskRules.canDeleteCompletion(viewer, task, it.toData()) }
                    .map { it.id }
            }
        if (deletable.isEmpty()) throw LocalForbiddenException("No completion of this task can be deleted")
        deletable.forEach { writer.delete(EntityTypes.TASK_COMPLETION, it) }
    }

    /** Adds an item at the end of the checklist and returns its id. */
    suspend fun addChecklistItem(
        taskId: String,
        text: String,
    ): String {
        val position =
            localDb.read {
                checklist_itemQueries
                    .selectByTask(taskId)
                    .executeAsList()
                    .maxOfOrNull { it.position.toInt() + 1 } ?: 0
            }
        val id = Ids.newId()
        upsertItem(id, ChecklistItemData(taskId, text, position))
        return id
    }

    suspend fun renameChecklistItem(
        itemId: String,
        text: String,
    ) = writer.upsert(EntityTypes.CHECKLIST_ITEM, itemId, JsonObject(mapOf("text" to JsonPrimitive(text))))

    /** Ticks or unticks an item; for a routine the tick belongs to the current occurrence (TSK-004). */
    suspend fun setChecklistItemDone(
        itemId: String,
        done: Boolean,
    ) {
        val occurrence =
            localDb.read {
                val item = checklist_itemQueries.selectById(itemId).executeAsOneOrNull() ?: return@read null
                val task = task(item.task_id)
                if (task.recurrence == null) null else currentState(item.task_id, task).occurrence
            }
        val fields =
            mapOf("done" to JsonPrimitive(done), "doneOccurrence" to (occurrence?.let { JsonPrimitive(it.toString()) } ?: JsonNull))
        writer.upsert(EntityTypes.CHECKLIST_ITEM, itemId, JsonObject(fields))
    }

    suspend fun deleteChecklistItem(itemId: String) = writer.delete(EntityTypes.CHECKLIST_ITEM, itemId)

    private suspend fun addCompletion(
        taskId: String,
        skipped: Boolean,
    ): String {
        // Milliseconds, as the typed table stores them, so the local row and the shadow agree.
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val data =
            localDb.read {
                val viewer = viewer()
                val task = task(taskId)
                TaskCompletionData(taskId, viewer.memberId, now, currentState(taskId, task, now).occurrence, skipped)
            }
        val id = Ids.newId()
        writer.upsert(EntityTypes.TASK_COMPLETION, id, encode(TaskCompletionData.serializer(), data))
        return id
    }

    private suspend fun upsertItem(
        id: String,
        data: ChecklistItemData,
    ) = writer.upsert(EntityTypes.CHECKLIST_ITEM, id, encode(ChecklistItemData.serializer(), data))

    private fun LaresDb.viewer(): Viewer = LocalSession.viewer(this) ?: throw LocalForbiddenException("Not logged in")

    private fun LaresDb.task(id: String): TaskData =
        taskQueries.selectById(id).executeAsOneOrNull()?.toData()
            ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Task not found")

    /** The same inputs as [TasksRepository]: the task's completions, today in the household zone. */
    private fun LaresDb.currentState(
        taskId: String,
        task: TaskData,
        now: Instant = clock.now(),
    ): TaskState {
        val zone =
            householdZone(
                householdQueries
                    .selectAll()
                    .executeAsList()
                    .firstOrNull()
                    ?.time_zone,
            )
        val completions = task_completionQueries.selectByTask(taskId).executeAsList().map { it.toData() }
        return TaskSchedule.state(task, completions, now.toLocalDateTime(zone).date, zone)
    }

    private fun <T> encode(
        serializer: KSerializer<T>,
        data: T,
    ): JsonObject = LaresJson.encodeToJsonElement(serializer, data).jsonObject
}
