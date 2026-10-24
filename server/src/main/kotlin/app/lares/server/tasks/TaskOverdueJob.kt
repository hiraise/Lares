package app.lares.server.tasks

import app.lares.contract.tasks.TaskSchedule
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.sync.lockHousehold
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Tells the creator that a task is overdue (TSK-033), also while every phone is offline
 * (architecture/tasks.md § Overdue job). One job serves all households.
 */
class TaskOverdueJob(
    private val db: AppDatabase,
    private val clock: Clock,
    private val notifier: TaskNotifier,
) {
    /** Visits tasks whose `overdue_check_at` has passed, oldest first, at most [BATCH] per run. Returns the notices sent. */
    suspend fun runOnce(now: Instant): Int {
        val nowDb = now.toDb()
        val due =
            db.dbQuery {
                TaskTable
                    .select(TaskTable.id, TaskTable.householdId)
                    .where { (TaskTable.deleted eq false) and (TaskTable.overdueCheckAt lessEq nowDb) }
                    .orderBy(TaskTable.overdueCheckAt to SortOrder.ASC)
                    .limit(BATCH)
                    .map { it[TaskTable.householdId] to it[TaskTable.id] }
            }
        if (due.isEmpty()) return 0
        var notices = 0
        for ((householdId, taskIds) in due.groupBy({ it.first }, { it.second })) {
            notices +=
                db.dbQuery {
                    // Same lock as every writer of the household: a task cannot change while it is visited.
                    lockHousehold(householdId)
                    val zone = TaskRepository.householdTimeZone(householdId)
                    taskIds.count { visit(householdId, it, now, zone) }
                }
        }
        log.info { "Overdue job: visited ${due.size} tasks, $notices notices" }
        return notices
    }

    /** Returns true if an overdue notice was scheduled. */
    private fun JdbcTransaction.visit(
        householdId: UUID,
        taskId: UUID,
        now: Instant,
        zone: TimeZone,
    ): Boolean {
        val row =
            TaskTable
                .selectAll()
                .where { (TaskTable.householdId eq householdId) and (TaskTable.id eq taskId) and (TaskTable.deleted eq false) }
                .singleOrNull() ?: return false
        // Changed since the query: a writer has already recomputed it.
        val checkAt = row[TaskTable.overdueCheckAt]
        if (checkAt == null || checkAt.toContract() > now) return false
        val task = TaskRepository.toTaskData(row)
        val completions = TaskRepository.completionsOf(householdId, taskId).map { it.data }
        val state = TaskSchedule.state(task, completions, now.toLocalDateTime(zone).date, zone)
        val occurrence = state.occurrence
        var notified = false
        if (!state.done && occurrence != null && row[TaskTable.overdueNotifiedOccurrence]?.toKotlinLocalDate() != occurrence) {
            val noticeAt = TaskSchedule.overdueNoticeAt(task, occurrence, zone)
            if (noticeAt <= now) {
                // No notice for a date that was already past when the task was saved, nor a stale one after downtime.
                if (noticeAt > row[TaskTable.updatedAt].toContract() && now - noticeAt <= STALE_AFTER) {
                    afterCommit { notifier.enqueue(TaskNotifier.Overdue(householdId, taskId)) }
                    notified = true
                }
                TaskTable.update({ (TaskTable.householdId eq householdId) and (TaskTable.id eq taskId) }) {
                    it[overdueNotifiedOccurrence] = occurrence.toJavaLocalDate()
                }
            }
        }
        TaskOverdueTracker.refresh(householdId, taskId, now)
        return notified
    }

    /** First run 30 s after start, then every 60 s; failures are logged and the next run tries again. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(30.seconds)
            while (isActive) {
                try {
                    runOnce(clock.now())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Overdue job failed" }
                }
                delay(60.seconds)
            }
        }

    companion object {
        const val BATCH = 500
        val STALE_AFTER = 24.hours
    }
}
