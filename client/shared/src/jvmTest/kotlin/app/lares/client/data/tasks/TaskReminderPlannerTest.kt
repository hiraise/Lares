package app.lares.client.data.tasks

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.notifications.AlarmScheduler
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.notifications.Reminders
import app.lares.client.platform.InMemoryServerStore
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.entities.TaskData
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TaskReminderPlannerTest {
    private class RecordingAlarms : AlarmScheduler {
        val set = mutableMapOf<String, Instant>()

        override fun set(
            id: String,
            at: Instant,
        ) {
            set[id] = at
        }

        override fun cancel(id: String) {
            set.remove(id)
        }
    }

    private val f = TaskFixture()
    private val alarms = RecordingAlarms()
    private val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            f.clock,
        )
    private val planner =
        TaskReminderPlanner(
            f.repository,
            f.localDb,
            Reminders(f.localDb, alarms, { _, _, _, _ -> }, f.clock),
            NotificationSettingsRepository(api, f.localDb),
            EnglishTaskReminderTexts(f.clock),
            f.clock,
        )
    private val today = LocalDate(2026, 10, 21)

    // Reminder 15 minutes before 20:00 Moscow = 16:45 UTC.
    private fun evening(assignees: Set<String> = emptySet()) =
        TaskData("Вынести мусор", OLGA, dueDate = today, dueTime = LocalTime(20, 0), assigneeIds = assignees, reminderMinutesBefore = 15)

    private suspend fun stored() = f.localDb.read { reminderQueries.all().executeAsList() }

    @Test
    fun schedulesForAssigneesAndAnyoneButNotForOthers() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val mine = f.tasksWriter.create(evening(setOf(OLGA)))
            val anyone = f.tasksWriter.create(evening())
            f.tasksWriter.create(evening(setOf(DIMA)))
            f.tasksWriter.create(TaskData("Без напоминания", OLGA, dueDate = today, assigneeIds = setOf(OLGA)))
            planner.reconcile()
            val at = Instant.parse("2026-10-21T16:45:00Z")
            assertEquals(mapOf("task:$mine" to at, "task:$anyone" to at), alarms.set)
            val row = stored().first()
            assertEquals("Вынести мусор", row.title)
            assertEquals("Due today at ${reminderTime(Instant.parse("2026-10-21T17:00:00Z"))}", row.body)
        }

    @Test
    fun aChangedDueTimeReschedulesAndCompletionCancels() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(evening(setOf(OLGA)))
            planner.reconcile()
            f.tasksWriter.update(id, evening(setOf(OLGA)).copy(dueTime = LocalTime(21, 0)))
            planner.reconcile()
            assertEquals(mapOf("task:$id" to Instant.parse("2026-10-21T17:45:00Z")), alarms.set)
            f.tasksWriter.complete(id)
            planner.reconcile()
            assertEquals(emptyMap(), alarms.set)
            assertEquals(emptyList(), stored())
        }

    @Test
    fun aCompletedRoutineIsRemindedForItsNextOccurrence() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val routine =
                evening(setOf(OLGA)).copy(
                    recurrence =
                        app.lares.contract.entities
                            .Recurrence(app.lares.contract.entities.RecurrenceMode.FIXED, app.lares.contract.entities.RecurrenceUnit.DAY),
                )
            val id = f.tasksWriter.create(routine)
            f.tasksWriter.complete(id)
            planner.reconcile()
            assertEquals(mapOf("task:$id" to Instant.parse("2026-10-22T16:45:00Z")), alarms.set)
        }

    @Test
    fun mutedCancelsAll() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            f.tasksWriter.create(evening(setOf(OLGA)))
            f.localDb.transaction { reminderQueries.put("other:1", 0, "Birthday", "", "reminders") }
            planner.reconcile()
            assertEquals(1, alarms.set.size)
            f.localDb.transaction { notification_muteQueries.put(NotificationType.TASK_REMINDER.key) }
            planner.reconcile()
            assertEquals(emptyMap(), alarms.set)
            // Reminders of other modules stay.
            assertEquals(listOf("other:1"), stored().map { it.id })
        }

    @Test
    fun pastAndFarRemindersAreNotScheduled() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            f.setNow(Instant.parse("2026-10-21T16:50:00Z"))
            f.tasksWriter.create(evening(setOf(OLGA)))
            f.tasksWriter.create(evening(setOf(OLGA)).copy(dueDate = LocalDate(2026, 12, 1)))
            val dateOnly = f.tasksWriter.create(TaskData("Утром", OLGA, dueDate = LocalDate(2026, 10, 22), reminderMinutesBefore = 0))
            planner.reconcile()
            // Date-only: 09:00 Moscow on the day.
            assertEquals(mapOf("task:$dateOnly" to Instant.parse("2026-10-22T06:00:00Z")), alarms.set)
        }
}
