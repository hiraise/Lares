package app.lares.client.data.tasks

import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class TasksRepositoryTest {
    private val f = TaskFixture()

    @Test
    fun aTimedTaskBecomesOverdueWhenTheTickerPassesItsDueTime() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            // Due today at 15:00 Moscow = 12:00 UTC.
            val id = f.tasksWriter.create(TaskData("Забрать посылку", OLGA, dueDate = LocalDate(2026, 10, 21), dueTime = LocalTime(15, 0)))
            f.setNow(Instant.parse("2026-10-21T11:59:00Z"))
            assertEquals(TaskGroup.TODAY, f.view(id).group)
            val views = f.repository.observeViews()
            f.setNow(Instant.parse("2026-10-21T12:00:00Z"))
            val overdue = views.first { list -> list.single().group == TaskGroup.OVERDUE }.single()
            assertEquals(true, overdue.overdue)
            assertEquals(Instant.parse("2026-10-21T12:00:00Z"), overdue.dueMoment)
            assertEquals(
                mapOf(TaskScope.ALL to 1, TaskScope.MINE to 1, TaskScope.ASSIGNED_BY_ME to 0),
                f.repository.observeCounts().first(),
            )
        }

    @Test
    fun aZoneChangeOfTheHouseholdMovesGroups() =
        runTest {
            f.family(zone = "UTC")
            f.loginAs(OLGA, *ADULT)
            // 22:30 UTC on Oct 21 is already 01:30 on Oct 22 in Moscow.
            f.setNow(Instant.parse("2026-10-21T22:30:00Z"))
            val id = f.tasksWriter.create(TaskData("Позвонить", OLGA, dueDate = LocalDate(2026, 10, 22)))
            assertEquals(TaskGroup.UPCOMING, f.view(id).group)
            f.setZone("Europe/Moscow")
            assertEquals(TaskGroup.TODAY, f.view(id).group)
        }

    @Test
    fun withoutAHouseholdThePhoneZoneIsUsedAndTagPagesListOpenTasks() =
        runTest {
            f.loginAs(OLGA, *ADULT)
            f.localDb.transaction { tagQueries.upsert("tag-car", "Машина", "blue", null, 0) }
            val open = f.tasksWriter.create(TaskData("Резина", OLGA, tagIds = setOf("tag-car")))
            val done = f.tasksWriter.create(TaskData("Мойка", OLGA, tagIds = setOf("tag-car")))
            f.tasksWriter.create(TaskData("Без метки", OLGA))
            f.tasksWriter.complete(done)
            assertEquals(
                listOf(open),
                f.repository
                    .observeForTag("tag-car")
                    .first()
                    .map { it.id },
            )
            assertEquals(listOf("Машина"), f.view(open).tags.map { it.data.name })
        }

    @Test
    fun minuteTicksEmitNowAndThenEveryMinuteBoundary() =
        runTest {
            val start = Instant.parse("2026-10-21T10:00:30Z")
            val clock =
                object : Clock {
                    override fun now() = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + testScheduler.currentTime)
                }
            val ticks = minuteTicks(clock).take(3).toList()
            assertEquals(
                listOf(start, Instant.parse("2026-10-21T10:01:00Z"), Instant.parse("2026-10-21T10:02:00Z")),
                ticks,
            )
        }
}
