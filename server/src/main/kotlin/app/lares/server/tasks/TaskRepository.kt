package app.lares.server.tasks

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import app.lares.server.sync.StoredEntity
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** A stored task completion with its id (for the overdue job and notifications, M1-03). */
data class StoredCompletion(
    val id: UUID,
    val data: TaskCompletionData,
)

/** Shared reads and row mapping of the tasks tables. All functions run inside `dbQuery`. */
object TaskRepository {
    private val idSet = SetSerializer(String.serializer())

    /** The task by id in any household (the sync handlers tell "other household" apart themselves). */
    fun load(id: UUID): StoredEntity<TaskData>? =
        TaskTable.selectAll().where { TaskTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[TaskTable.householdId], it[TaskTable.deleted], toTaskData(it))
        }

    /** The task of [householdId] with [id], tombstoned or not; null if missing or in another household. */
    fun get(
        householdId: UUID,
        id: UUID,
    ): StoredEntity<TaskData>? = load(id)?.takeIf { it.householdId == householdId }

    /** Non-deleted completions of a task, oldest first. */
    fun completionsOf(
        householdId: UUID,
        taskId: UUID,
    ): List<StoredCompletion> =
        TaskCompletionTable
            .selectAll()
            .where {
                (TaskCompletionTable.householdId eq householdId) and
                    (TaskCompletionTable.taskId eq taskId) and
                    (TaskCompletionTable.deleted eq false)
            }.orderBy(TaskCompletionTable.completedAt to SortOrder.ASC, TaskCompletionTable.id to SortOrder.ASC)
            .map { StoredCompletion(it[TaskCompletionTable.id], toCompletionData(it)) }

    /** The household time zone that task dates and times are in. */
    fun householdTimeZone(householdId: UUID): TimeZone =
        TimeZone.of(
            HouseholdTable
                .select(HouseholdTable.timeZone)
                .where { HouseholdTable.id eq householdId }
                .single()[HouseholdTable.timeZone],
        )

    fun toTaskData(row: ResultRow) =
        TaskData(
            title = row[TaskTable.title],
            createdBy = row[TaskTable.createdBy].toString(),
            notes = row[TaskTable.notes],
            dueDate = row[TaskTable.dueDate]?.toKotlinLocalDate(),
            dueTime = row[TaskTable.dueTime]?.toKotlinLocalTime(),
            assigneeIds = LaresJson.decodeFromString(idSet, row[TaskTable.assigneeIds]),
            tagIds = LaresJson.decodeFromString(idSet, row[TaskTable.tagIds]),
            recurrence = row[TaskTable.recurrence]?.let { LaresJson.decodeFromString(Recurrence.serializer(), it) },
            reminderMinutesBefore = row[TaskTable.reminderMinutesBefore],
            notifyCreatorOnDone = row[TaskTable.notifyCreatorOnDone],
        )

    fun writeTask(
        it: UpdateBuilder<*>,
        data: TaskData,
    ) {
        it[TaskTable.title] = data.title
        it[TaskTable.createdBy] = UUID.fromString(data.createdBy)
        it[TaskTable.notes] = data.notes
        it[TaskTable.dueDate] = data.dueDate?.toJavaLocalDate()
        it[TaskTable.dueTime] = data.dueTime?.toJavaLocalTime()
        it[TaskTable.assigneeIds] = LaresJson.encodeToString(idSet, data.assigneeIds)
        it[TaskTable.tagIds] = LaresJson.encodeToString(idSet, data.tagIds)
        it[TaskTable.recurrence] = data.recurrence?.let { rule -> LaresJson.encodeToString(Recurrence.serializer(), rule) }
        it[TaskTable.reminderMinutesBefore] = data.reminderMinutesBefore
        it[TaskTable.notifyCreatorOnDone] = data.notifyCreatorOnDone
    }

    fun toChecklistItemData(row: ResultRow) =
        ChecklistItemData(
            taskId = row[ChecklistItemTable.taskId].toString(),
            text = row[ChecklistItemTable.text],
            position = row[ChecklistItemTable.position],
            done = row[ChecklistItemTable.done],
            doneOccurrence = row[ChecklistItemTable.doneOccurrence]?.toKotlinLocalDate(),
        )

    fun writeChecklistItem(
        it: UpdateBuilder<*>,
        data: ChecklistItemData,
    ) {
        it[ChecklistItemTable.taskId] = UUID.fromString(data.taskId)
        it[ChecklistItemTable.text] = data.text
        it[ChecklistItemTable.position] = data.position
        it[ChecklistItemTable.done] = data.done
        it[ChecklistItemTable.doneOccurrence] = data.doneOccurrence?.toJavaLocalDate()
    }

    fun toCompletionData(row: ResultRow) =
        TaskCompletionData(
            taskId = row[TaskCompletionTable.taskId].toString(),
            completedBy = row[TaskCompletionTable.completedBy].toString(),
            completedAt = row[TaskCompletionTable.completedAt].toContract(),
            occurrence = row[TaskCompletionTable.occurrence]?.toKotlinLocalDate(),
            skipped = row[TaskCompletionTable.skipped],
        )

    fun writeCompletion(
        it: UpdateBuilder<*>,
        data: TaskCompletionData,
    ) {
        it[TaskCompletionTable.taskId] = UUID.fromString(data.taskId)
        it[TaskCompletionTable.completedBy] = UUID.fromString(data.completedBy)
        it[TaskCompletionTable.completedAt] = data.completedAt.toDb()
        it[TaskCompletionTable.occurrence] = data.occurrence?.toJavaLocalDate()
        it[TaskCompletionTable.skipped] = data.skipped
    }
}
