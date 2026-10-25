package app.lares.client.data.calendar

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.PersonsRepository
import app.lares.client.data.TagsRepository
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.data.tasks.TestClock
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.sync.EventAdapter
import app.lares.client.sync.EventOccurrenceAdapter
import app.lares.client.sync.EventReminderAdapter
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.TaskAdapter
import app.lares.client.sync.defaultEntityAdapters
import app.lares.client.sync.testLocalDb
import app.lares.contract.Ids
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/** Member ids of the fixture family. */
const val OLGA = "01928c4e-0000-7000-8000-00000000000a"
const val DIMA = "01928c4e-0000-7000-8000-00000000000b"
const val ANYA = "01928c4e-0000-7000-8000-00000000000c"

/** Person ids: the three members and a relative without a login. */
const val P_OLGA = "01928c4e-0000-7000-8000-0000000000a1"
const val P_DIMA = "01928c4e-0000-7000-8000-0000000000b1"
const val P_ANYA = "01928c4e-0000-7000-8000-0000000000c1"
const val P_VALYA = "01928c4e-0000-7000-8000-0000000000d1"

val CAL_ADULT = arrayOf(Permission.CALENDAR_VIEW, Permission.CALENDAR_CREATE, Permission.TASKS_VIEW, Permission.TASKS_CREATE)
val CAL_KID = arrayOf(Permission.CALENDAR_VIEW, Permission.CALENDAR_CREATE, Permission.TASKS_VIEW)

/**
 * One offline client core for calendar tests (copy of `TaskFixture` with persons): in-memory database, a never-started
 * sync engine, the calendar repository with a hand-driven ticker and a [TestClock]. Starts on Wednesday 2026-10-21
 * 13:00 in Moscow (10:00 UTC). Persons sort by name: Аня, Бабушка Валя, Дмитрий, Ольга.
 */
class CalendarFixture(
    start: Instant = Instant.parse("2026-10-21T10:00:00Z"),
) {
    val clock = TestClock(start)
    val localDb = testLocalDb()
    val adapters = defaultEntityAdapters(clock)
    val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            clock,
        )
    private val engine = SyncEngine(api, localDb, adapters, Materializer(adapters), clock, CoroutineScope(Job()))
    val writer = LocalWriter(localDb, adapters, clock, engine)
    val calendarWriter = CalendarWriter(writer, localDb, clock)
    val tasksWriter = TasksWriter(writer, localDb, clock)
    val ticks = MutableStateFlow(start)
    private val members = MembersRepository(localDb)
    private val tags = TagsRepository(localDb)
    private val household = HouseholdRepository(localDb)
    private val permissions = PermissionsRepository(localDb)
    val tasks = TasksRepository(localDb, members, tags, household, permissions, clock, ticks)
    val repository =
        CalendarRepository(localDb, members, PersonsRepository(localDb), tags, household, permissions, tasks, clock, ticks)

    fun setNow(now: Instant) {
        clock.now = now
        ticks.value = now
    }

    /** Ольга, Дмитрий and Аня are members; Бабушка Валя is a relative born 1948-10-24. */
    suspend fun family() =
        localDb.transaction {
            householdQueries.upsert("h", "Петровы", "Europe/Moscow", "RUB", "ru")
            personQueries.upsert(P_OLGA, "Ольга", 1985, 3, 1, null, null, "[]", "red")
            personQueries.upsert(P_DIMA, "Дмитрий", null, null, null, null, null, "[]", "blue")
            personQueries.upsert(P_ANYA, "Аня", 2015, 10, 23, null, null, "[]", "green")
            personQueries.upsert(P_VALYA, "Бабушка Валя", 1948, 10, 24, "Бабушка", null, "[]", "purple")
            memberQueries.upsert(OLGA, P_OLGA, null, null, 0)
            memberQueries.upsert(DIMA, P_DIMA, null, null, 0)
            memberQueries.upsert(ANYA, P_ANYA, null, null, 0)
        }

    suspend fun loginAs(
        memberId: String,
        vararg permissions: Permission,
    ) = localDb.transaction {
        val person = mapOf(OLGA to P_OLGA, DIMA to P_DIMA, ANYA to P_ANYA).getValue(memberId)
        LocalSession.save(
            this,
            Me("u", memberId, "h", "Петровы", person, "Me", "me", true, isAdmin = false, permissions = permissions.toSet()),
        )
    }

    /** Rows as if they had come from the server. */
    suspend fun putEvent(
        id: String,
        data: EventData,
    ) = localDb.transaction { (adapters[EntityTypes.EVENT] as EventAdapter).upsertTyped(this, id, data) }

    suspend fun putRow(data: EventOccurrenceData) =
        localDb.transaction {
            (adapters[EntityTypes.EVENT_OCCURRENCE] as EventOccurrenceAdapter)
                .upsertTyped(this, Ids.occurrenceRowId(data.eventId, data.occurrence), data)
        }

    suspend fun putReminder(data: EventReminderData) =
        localDb.transaction {
            (adapters[EntityTypes.EVENT_REMINDER] as EventReminderAdapter)
                .upsertTyped(this, Ids.reminderRowId(data.eventId, data.memberId), data)
        }

    suspend fun putTask(
        id: String,
        data: TaskData,
    ) = localDb.transaction { (adapters[EntityTypes.TASK] as TaskAdapter).upsertTyped(this, id, data) }

    suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    suspend fun inputs() = repository.observeInputs().first()

    suspend fun rows(eventId: String) = localDb.read { event_occurrenceQueries.selectByEvent(eventId).executeAsList() }
}

/** A timed one-off event of [OLGA] (unless [createdBy] says otherwise). */
fun timedEvent(
    title: String,
    date: LocalDate,
    start: LocalTime,
    end: LocalTime,
    participants: Set<String> = emptySet(),
    createdBy: String = OLGA,
    endDate: LocalDate = date,
) = EventData(title, createdBy, date, endDate, startTime = start, endTime = end, participantIds = participants)

fun allDayEvent(
    title: String,
    start: LocalDate,
    end: LocalDate = start,
    participants: Set<String> = emptySet(),
) = EventData(title, OLGA, start, end, allDay = true, participantIds = participants)
