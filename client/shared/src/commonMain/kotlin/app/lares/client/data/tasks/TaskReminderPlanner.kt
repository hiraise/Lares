package app.lares.client.data.tasks

import app.lares.client.db.LocalDb
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.notifications.ReminderScheduler
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.tasks.TaskGroup
import app.lares.contract.tasks.TaskSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The body of a task reminder in the app language, e.g. "Due today at 20:00" / "Срок: сегодня в 20:00". The core binds
 * [EnglishTaskReminderTexts]; the app UI binds a resource-backed version. [dueMoment] is null for date-only tasks.
 */
interface TaskReminderTexts {
    suspend fun body(
        dueMoment: Instant?,
        occurrence: LocalDate,
    ): String
}

/** Which day words a reminder body uses, relative to the phone's current day. */
enum class ReminderDay { TODAY, TOMORROW, OTHER }

fun reminderDay(
    occurrence: LocalDate,
    clock: Clock,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): ReminderDay {
    val today = clock.now().toLocalDateTime(zone).date
    return when (occurrence) {
        today -> ReminderDay.TODAY
        today.plus(1, DateTimeUnit.DAY) -> ReminderDay.TOMORROW
        else -> ReminderDay.OTHER
    }
}

/** "9:05" in the phone's zone. */
fun reminderTime(
    at: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = at.toLocalDateTime(zone)
    return "${local.hour}:${local.minute.toString().padStart(2, '0')}"
}

/** Fallback without UI resources (integration tests, a core without the app UI). */
class EnglishTaskReminderTexts(
    private val clock: Clock,
) : TaskReminderTexts {
    override suspend fun body(
        dueMoment: Instant?,
        occurrence: LocalDate,
    ): String {
        val day =
            when (reminderDay(occurrence, clock)) {
                ReminderDay.TODAY -> "today"
                ReminderDay.TOMORROW -> "tomorrow"
                ReminderDay.OTHER -> occurrence.toString()
            }
        return if (dueMoment == null) "Due $day" else "Due $day at ${reminderTime(dueMoment)}"
    }
}

/**
 * Local reminders of the member's tasks (architecture/tasks.md § Reminders, TSK-030): makes the stored reminders with
 * ids `task:<taskId>` match the wanted ones — open tasks with a reminder, assigned to the member or to "anyone", whose
 * reminder time is in the future and at most 30 days ahead; none while `task.reminder` is muted.
 */
class TaskReminderPlanner(
    private val repository: TasksRepository,
    private val localDb: LocalDb,
    private val reminders: ReminderScheduler,
    private val settings: NotificationSettingsRepository,
    private val texts: TaskReminderTexts,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun reconcile() =
        mutex.withLock {
            val wanted = if (settings.isMuted(NotificationType.TASK_REMINDER)) emptyMap() else wanted()
            val stored = localDb.read { reminderQueries.selectByPrefix("$ID_PREFIX%").executeAsList() }.associateBy { it.id }
            for ((id, reminder) in wanted) {
                val row = stored[id]
                val same =
                    row != null &&
                        row.at == reminder.at.toEpochMilliseconds() &&
                        row.title == reminder.title &&
                        row.body == reminder.body &&
                        row.channel == NotificationChannel.REMINDERS.id
                if (!same) reminders.schedule(id, reminder.at, reminder.title, reminder.body, NotificationChannel.REMINDERS)
            }
            (stored.keys - wanted.keys).forEach { reminders.cancel(it) }
        }

    /**
     * Reconciles after every change of the tasks (local writes, syncs; debounced 1 s) and of the mute settings, until
     * the returned job is cancelled (logout).
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            combine(repository.observeViews(), settings.muted) { _, _ -> }
                .debounce(1.seconds)
                .collectLatest {
                    try {
                        reconcile()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Retried on the next change and after the next background sync.
                    }
                }
        }

    private suspend fun wanted(): Map<String, Wanted> {
        val now = clock.now()
        val inputs = repository.observeInputs().first().copy(now = now)
        val me = inputs.viewer?.memberId ?: return emptyMap()
        val horizon = now + MAX_AHEAD
        val result = mutableMapOf<String, Wanted>()
        for (view in TaskViews.build(inputs)) {
            val task = view.data
            val occurrence = view.state.occurrence ?: continue
            if (view.group == TaskGroup.DONE || task.reminderMinutesBefore == null) continue
            if (task.assigneeIds.isNotEmpty() && me !in task.assigneeIds) continue
            val at = TaskSchedule.reminderAt(task, occurrence, inputs.zone) ?: continue
            if (at <= now || at > horizon) continue
            result["$ID_PREFIX${view.id}"] = Wanted(at, task.title, texts.body(view.dueMoment, occurrence))
        }
        return result
    }

    private data class Wanted(
        val at: Instant,
        val title: String,
        val body: String,
    )

    companion object {
        const val ID_PREFIX = "task:"
        val MAX_AHEAD = 30.days
    }
}
