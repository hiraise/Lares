package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

// Helpers for tasks tests (M1-02); reused by M1-03 and integration-tests.

/** A JSON array of strings, e.g. for `assigneeIds`, `tagIds` or `weekdays`. */
fun jsonStrings(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))

/** A repeat rule as JSON (`Recurrence`). */
fun recurrenceJson(
    mode: String = "fixed",
    unit: String = "day",
    interval: Int = 1,
    weekdays: List<String> = emptyList(),
): JsonObject = fields("mode" to mode, "unit" to unit, "interval" to interval, "weekdays" to JsonArray(weekdays.map(::JsonPrimitive)))

/** A new task by [createdBy] (a member id). [extra] adds or overrides fields, e.g. `mapOf("dueDate" to "2026-10-23")`. */
fun AppDeps.newTask(
    createdBy: String,
    title: String = "Take out the trash",
    assigneeIds: List<String> = emptyList(),
    extra: Map<String, Any?> = emptyMap(),
    id: String = Ids.newId(),
): Mutation {
    val base: Map<String, JsonElement> =
        fields("title" to title, "createdBy" to createdBy, "assigneeIds" to JsonArray(assigneeIds.map(::JsonPrimitive)))
    return upsert(EntityTypes.TASK, id, JsonObject(base + fields(*extra.toList().toTypedArray())))
}

/** A completion of [taskId] by [by] (a member id); [occurrence] as an ISO date or null. */
fun AppDeps.newCompletion(
    taskId: String,
    by: String,
    occurrence: String? = null,
    at: Instant = clock.now(),
    skipped: Boolean = false,
    id: String = Ids.newId(),
): Mutation =
    upsert(
        EntityTypes.TASK_COMPLETION,
        id,
        fields(
            "taskId" to taskId,
            "completedBy" to by,
            "completedAt" to at.toString(),
            "occurrence" to occurrence,
            "skipped" to skipped,
        ),
    )

fun AppDeps.newChecklistItem(
    taskId: String,
    text: String,
    position: Int = 0,
    id: String = Ids.newId(),
): Mutation = upsert(EntityTypes.CHECKLIST_ITEM, id, fields("taskId" to taskId, "text" to text, "position" to position))
