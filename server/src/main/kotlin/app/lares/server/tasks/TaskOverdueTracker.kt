package app.lares.server.tasks

import app.lares.contract.tasks.TaskSchedule
import app.lares.server.platform.toDb
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Instant

/**
 * Keeps `task.overdue_check_at` — the next moment the overdue job must look at a task — up to date
 * (architecture/tasks.md § Overdue job). Server-only column: written without `recordChange`, so no seq and no sync change.
 */
object TaskOverdueTracker {
    /**
     * Recomputes `overdue_check_at` of the task; runs inside `dbQuery` after every write to the task or its completions
     * and after each job visit. A tombstoned task gets `null`.
     */
    fun refresh(
        householdId: UUID,
        taskId: UUID,
        now: Instant,
    ) {
        val row =
            TaskTable
                .selectAll()
                .where { (TaskTable.householdId eq householdId) and (TaskTable.id eq taskId) }
                .singleOrNull() ?: return
        val next =
            if (row[TaskTable.deleted]) {
                null
            } else {
                TaskSchedule.nextOverdueCheck(
                    task = TaskRepository.toTaskData(row),
                    completions = TaskRepository.completionsOf(householdId, taskId).map { it.data },
                    notifiedOccurrence = row[TaskTable.overdueNotifiedOccurrence]?.toKotlinLocalDate(),
                    now = now,
                    zone = TaskRepository.householdTimeZone(householdId),
                )
            }
        val stored = row[TaskTable.overdueCheckAt]
        val value = next?.toDb()
        if (stored == value) return
        TaskTable.update({ (TaskTable.householdId eq householdId) and (TaskTable.id eq taskId) }) {
            it[overdueCheckAt] = value
        }
    }
}
