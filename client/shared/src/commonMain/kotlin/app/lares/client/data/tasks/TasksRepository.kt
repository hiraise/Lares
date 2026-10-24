package app.lares.client.data.tasks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MemberView
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.db.LocalDb
import app.lares.client.sync.toData
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.permissions.Viewer
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/** Emits [Clock.now] right away and then at every minute boundary, so overdue states change without data changes. */
fun minuteTicks(clock: Clock): Flow<Instant> =
    flow {
        while (true) {
            val now = clock.now()
            emit(now)
            delay(MINUTE_MS - now.toEpochMilliseconds().mod(MINUTE_MS))
        }
    }

private const val MINUTE_MS = 60_000L

/** The household's zone, or the phone's when it is unknown (before the first sync) or invalid. */
internal fun householdZone(timeZone: String?): TimeZone =
    timeZone?.let {
        try {
            TimeZone.of(it)
        } catch (_: IllegalTimeZoneException) {
            null
        }
    } ?: TimeZone.currentSystemDefault()

/**
 * Read side of the tasks module (architecture/tasks.md § Views): combines the three task tables, members, tags, the
 * household zone, the member's own permissions and a minute ticker, and computes the views in Kotlin.
 * [ticks] is [minuteTicks] of [clock]; tests pass their own.
 */
class TasksRepository(
    private val localDb: LocalDb,
    private val members: MembersRepository,
    private val tags: TagsRepository,
    private val household: HouseholdRepository,
    private val permissions: PermissionsRepository,
    clock: Clock,
    private val ticks: Flow<Instant> = minuteTicks(clock),
) {
    private val db get() = localDb.db

    private fun rows() =
        combine(
            db.taskQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.checklist_itemQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.task_completionQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
        ) { tasks, items, completions ->
            Triple(
                tasks.map { it.id to it.toData() },
                items.map { it.id to it.toData() },
                completions.map { TaskCompletion(it.id, it.toData()) },
            )
        }

    private fun viewer(): Flow<Viewer?> {
        val me =
            db.local_sessionQueries
                .get()
                .asFlow()
                .mapToOneOrNull(localDb.dispatcher)
                .map { json -> json?.let { LaresJson.decodeFromString(Me.serializer(), it) } }
        return combine(me, permissions.observeMine()) { current, mine ->
            current?.let { Viewer(it.memberId, it.personId, mine) }
        }
    }

    /** All inputs of the views; a new value on every data change and every minute. */
    fun observeInputs(): Flow<TaskInputs> {
        val context =
            combine(
                members.observeAll(),
                tags.observeAll(includeArchived = true),
                household.observe().map { householdZone(it?.data?.timeZone) },
                viewer(),
            ) { allMembers, allTags, zone, viewer -> Context(allMembers, allTags, zone, viewer) }
        return combine(rows(), context, ticks) { (tasks, items, completions), ctx, now ->
            TaskInputs(tasks, items, completions, ctx.members, ctx.tags, ctx.zone, ctx.viewer, now)
        }
    }

    fun observeViews(): Flow<List<TaskView>> = observeInputs().map(TaskViews::build).distinctUntilChanged()

    fun observeDetail(id: String): Flow<TaskDetail?> = observeInputs().map { TaskViews.detail(it, id) }.distinctUntilChanged()

    /** Open tasks per scope for the chips. */
    fun observeCounts(): Flow<Map<TaskScope, Int>> =
        observeInputs()
            .map { TaskViews.counts(TaskViews.build(it), it.viewer?.memberId) }
            .distinctUntilChanged()

    /** Open tasks with the tag, in section order (tag page, TAG-004). */
    fun observeForTag(tagId: String): Flow<List<TaskView>> =
        observeViews()
            .map { views ->
                val open = views.filter { tagId in it.data.tagIds && it.group != TaskGroup.DONE }
                TaskViews.sections(open).flatMap { it.tasks }
            }.distinctUntilChanged()

    private data class Context(
        val members: List<MemberView>,
        val tags: List<Tag>,
        val zone: TimeZone,
        val viewer: Viewer?,
    )
}
