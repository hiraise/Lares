package app.lares.server.tasks

import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the tasks module (V6__tasks.sql, architecture/tasks.md § Server › Tables).

/** `task`: a task or routine (TSK-001…TSK-011). Id sets and the repeat rule are JSON text encoded with `LaresJson`. */
object TaskTable : Table("task") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val title = text("title")
    val notes = text("notes").nullable()
    val createdBy = javaUUID("created_by").references(MemberTable.id)
    val dueDate = date("due_date").nullable()
    val dueTime = time("due_time").nullable()
    val assigneeIds = text("assignee_ids").default("[]")
    val tagIds = text("tag_ids").default("[]")
    val recurrence = text("recurrence").nullable()
    val reminderMinutesBefore = integer("reminder_minutes_before").nullable()
    val notifyCreatorOnDone = bool("notify_creator_on_done").default(false)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    /** Server-only, never synced (overdue job, M1-03). */
    val overdueCheckAt = timestamp("overdue_check_at").nullable()
    val overdueNotifiedOccurrence = date("overdue_notified_occurrence").nullable()

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `checklist_item` (TSK-004). */
object ChecklistItemTable : Table("checklist_item") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val taskId = javaUUID("task_id").references(TaskTable.id, onDelete = ReferenceOption.CASCADE)
    val text = text("text")
    val position = integer("position")
    val done = bool("done").default(false)
    val doneOccurrence = date("done_occurrence").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `task_completion`: a completed or skipped occurrence (TSK-003, ADR-0017). Immutable; undo tombstones it. */
object TaskCompletionTable : Table("task_completion") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val taskId = javaUUID("task_id").references(TaskTable.id, onDelete = ReferenceOption.CASCADE)
    val completedBy = javaUUID("completed_by").references(MemberTable.id)
    val completedAt = timestamp("completed_at")
    val occurrence = date("occurrence").nullable()
    val skipped = bool("skipped").default(false)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}
