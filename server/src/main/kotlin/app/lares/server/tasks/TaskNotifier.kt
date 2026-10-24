package app.lares.server.tasks

import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.household.MemberRepository
import app.lares.server.household.PermissionService
import app.lares.server.household.PersonTable
import app.lares.server.notifications.NotificationService
import app.lares.server.notifications.NotificationTexts
import app.lares.server.platform.db.AppDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/**
 * Task event notifications (TSK-031…TSK-033, architecture/tasks.md § Notifications). Handlers and the overdue job
 * [enqueue] events through `afterCommit`; a consumer coroutine ([start]) resolves recipients and texts and sends them
 * with [NotificationService.notify]. Recipients are non-deleted members with `tasks.view`, never the actor.
 */
class TaskNotifier(
    private val db: AppDatabase,
    private val permissions: PermissionService,
    private val notifications: NotificationService,
) {
    sealed interface Event {
        val householdId: UUID
        val taskId: UUID
    }

    /** [memberIds] were added to the task's assignees by [actorMemberId]. */
    data class Assigned(
        override val householdId: UUID,
        override val taskId: UUID,
        val actorMemberId: UUID,
        val memberIds: Set<UUID>,
    ) : Event

    /** [actorMemberId] completed the task's occurrence first; goes to the creator. */
    data class Completed(
        override val householdId: UUID,
        override val taskId: UUID,
        val actorMemberId: UUID,
    ) : Event

    /** The task's current occurrence is overdue; goes to the creator. */
    data class Overdue(
        override val householdId: UUID,
        override val taskId: UUID,
    ) : Event

    private val log = KotlinLogging.logger {}
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Events enqueued and not yet processed by the consumer. */
    private val pending = MutableStateFlow(0)

    /** Never blocks; call it from `afterCommit`. */
    fun enqueue(event: Event) {
        pending.update { it + 1 }
        if (events.trySend(event).isFailure) pending.update { it - 1 }
    }

    /** The consumer: processes events one by one; failures are logged and the loop goes on. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            for (event in events) {
                try {
                    process(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Task notification ${event::class.simpleName} for task ${event.taskId} failed" }
                } finally {
                    pending.update { it - 1 }
                }
            }
        }

    /** Suspends until every enqueued event has been processed (tests use it instead of waiting). */
    suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    private class Prepared(
        val type: NotificationType,
        val recipients: List<UUID>,
        val title: String,
        val actorName: String,
    )

    suspend fun process(event: Event) {
        val prepared = db.dbQuery { prepare(event) } ?: return
        if (prepared.recipients.isEmpty()) return
        val results =
            notifications.notify(
                event.householdId,
                prepared.recipients,
                prepared.type,
                entityType = EntityTypes.TASK,
                entityId = event.taskId.toString(),
            ) { language ->
                val text =
                    when (prepared.type) {
                        NotificationType.TASK_ASSIGNED -> NotificationTexts.taskAssigned(language, prepared.title, prepared.actorName)
                        NotificationType.TASK_COMPLETED -> NotificationTexts.taskCompleted(language, prepared.title, prepared.actorName)
                        else -> NotificationTexts.taskOverdue(language, prepared.title)
                    }
                text.title to text.body
            }
        log.debug { "${prepared.type.key} for task ${event.taskId}: ${prepared.recipients.size} members, ${results.size} sessions" }
    }

    /** Runs inside `dbQuery`. Null when the task is gone. */
    private fun prepare(event: Event): Prepared? {
        val stored = TaskRepository.get(event.householdId, event.taskId) ?: return null
        if (stored.deleted) return null
        val task = stored.data
        val creator = UUID.fromString(task.createdBy)
        val (type, candidates, actor) =
            when (event) {
                is Assigned -> Triple(NotificationType.TASK_ASSIGNED, event.memberIds, event.actorMemberId)
                is Completed -> Triple(NotificationType.TASK_COMPLETED, setOf(creator), event.actorMemberId)
                is Overdue -> Triple(NotificationType.TASK_OVERDUE, setOf(creator), null)
            }
        val recipients =
            candidates.filter { memberId ->
                memberId != actor &&
                    MemberRepository.get(event.householdId, memberId)?.deleted == false &&
                    permissions.effectiveInTransaction(event.householdId, memberId).has(Permission.TASKS_VIEW)
            }
        val actorName = actor?.let { personName(event.householdId, it) }.orEmpty()
        return Prepared(type, recipients, task.title, actorName)
    }

    private fun personName(
        householdId: UUID,
        memberId: UUID,
    ): String? {
        val member = MemberRepository.get(householdId, memberId) ?: return null
        return PersonTable
            .select(PersonTable.name)
            .where { PersonTable.id eq member.personId }
            .singleOrNull()
            ?.get(PersonTable.name)
    }
}
