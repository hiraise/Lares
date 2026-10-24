package app.lares.client.data.tasks

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.TagsRepository
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.sync.ChecklistItemAdapter
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.TaskAdapter
import app.lares.client.sync.defaultEntityAdapters
import app.lares.client.sync.testLocalDb
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock the test moves by hand. */
class TestClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** Member ids of the fixture family. */
const val OLGA = "01928c4e-0000-7000-8000-00000000000a"
const val DIMA = "01928c4e-0000-7000-8000-00000000000b"
const val ANYA = "01928c4e-0000-7000-8000-00000000000c"

val ADULT = arrayOf(Permission.TASKS_VIEW, Permission.TASKS_CREATE)
val ADMIN_LIKE = arrayOf(Permission.TASKS_VIEW, Permission.TASKS_CREATE, Permission.TASKS_EDIT_ANY)
val KID = arrayOf(Permission.TASKS_VIEW)

/**
 * One offline client core for task tests: in-memory database, a never-started sync engine, the tasks repository with
 * a hand-driven [ticks] flow and a [TestClock]. Starts on Wednesday 2026-10-21 13:00 in Moscow (10:00 UTC).
 */
class TaskFixture(
    start: Instant = Instant.parse("2026-10-21T10:00:00Z"),
) {
    val clock = TestClock(start)
    val localDb = testLocalDb()
    val adapters = defaultEntityAdapters(clock)
    private val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            clock,
        )
    private val engine = SyncEngine(api, localDb, adapters, Materializer(adapters), clock, CoroutineScope(Job()))
    val writer = LocalWriter(localDb, adapters, clock, engine)
    val tasksWriter = TasksWriter(writer, localDb, clock)
    val ticks = MutableStateFlow(start)
    val repository =
        TasksRepository(
            localDb,
            MembersRepository(localDb),
            TagsRepository(localDb),
            HouseholdRepository(localDb),
            PermissionsRepository(localDb),
            clock,
            ticks,
        )

    /** Moves the clock and the ticker. */
    fun setNow(now: Instant) {
        clock.now = now
        ticks.value = now
    }

    suspend fun family(zone: String = "Europe/Moscow") =
        localDb.transaction {
            householdQueries.upsert("h", "Петровы", zone, "RUB", "ru")
            listOf(OLGA to "Ольга", DIMA to "Дмитрий", ANYA to "Аня").forEachIndexed { i, (id, name) ->
                personQueries.upsert("person-$i", name, null, null, null, null, null, "[]", null)
                memberQueries.upsert(id, "person-$i", null, null, 0)
            }
        }

    suspend fun setZone(zone: String) = localDb.transaction { householdQueries.upsert("h", "Петровы", zone, "RUB", "ru") }

    suspend fun loginAs(
        memberId: String,
        vararg permissions: Permission,
    ) = localDb.transaction {
        LocalSession.save(
            this,
            Me("u", memberId, "h", "Петровы", "p", "Me", "me", true, isAdmin = false, permissions = permissions.toSet()),
        )
    }

    /** A task row as if it had come from the server. */
    suspend fun putTask(
        id: String,
        data: TaskData,
    ) = localDb.transaction { (adapters[EntityTypes.TASK] as TaskAdapter).upsertTyped(this, id, data) }

    suspend fun putItem(
        id: String,
        data: ChecklistItemData,
    ) = localDb.transaction { (adapters[EntityTypes.CHECKLIST_ITEM] as ChecklistItemAdapter).upsertTyped(this, id, data) }

    suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    suspend fun views() = repository.observeViews().first()

    suspend fun view(id: String) = views().single { it.id == id }

    suspend fun detail(id: String) = repository.observeDetail(id).first()!!
}
