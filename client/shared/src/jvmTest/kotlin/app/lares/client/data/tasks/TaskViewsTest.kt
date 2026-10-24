package app.lares.client.data.tasks

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import app.lares.contract.tasks.TaskGroup
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskViewsTest {
    private val zone = TimeZone.of("Europe/Moscow")

    // Wednesday 2026-10-21, 13:00 in Moscow.
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val today = LocalDate(2026, 10, 21)
    private val daily = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY)
    private val members =
        listOf(OLGA to "Ольга", DIMA to "Дмитрий").map { (id, name) ->
            MemberView(id, MemberData("p-$id", null, null, false), name, PaletteColor.BLUE)
        }
    private val tags =
        listOf(Tag("tag-car", TagData("Машина", PaletteColor.BLUE)), Tag("tag-old", TagData("Старое", PaletteColor.GRAY, archived = true)))
    private val me = Viewer(OLGA, "p", EffectivePermissions(false, setOf(Permission.TASKS_VIEW, Permission.TASKS_CREATE)))

    private var counter = 0

    private fun id() = "01928c4e-0000-7000-8000-%012d".format(++counter)

    private fun task(
        title: String = "Task",
        createdBy: String = OLGA,
        due: LocalDate? = null,
        time: LocalTime? = null,
        assignees: Set<String> = emptySet(),
        tagIds: Set<String> = emptySet(),
        recurrence: Recurrence? = null,
    ) = id() to TaskData(title, createdBy, dueDate = due, dueTime = time, assigneeIds = assignees, tagIds = tagIds, recurrence = recurrence)

    private fun completion(
        taskId: String,
        at: Instant,
        occurrence: LocalDate? = null,
        skipped: Boolean = false,
        by: String = OLGA,
    ) = TaskCompletion(id(), TaskCompletionData(taskId, by, at, occurrence, skipped))

    private fun build(
        tasks: List<Pair<String, TaskData>>,
        completions: List<TaskCompletion> = emptyList(),
        checklist: List<Pair<String, ChecklistItemData>> = emptyList(),
    ) = TaskViews.build(TaskInputs(tasks, checklist, completions, members, tags, zone, me, now))

    @Test
    fun mineIsAssignedToMeOrAnyone() {
        val mine = task(assignees = setOf(OLGA, DIMA))
        val anyone = task()
        val other = task(assignees = setOf(DIMA))
        val rows = TaskViews.filter(build(listOf(mine, anyone, other)), TaskFilter(TaskScope.MINE), OLGA)
        assertEquals(listOf(mine.first, anyone.first), rows.map { it.id })
    }

    @Test
    fun assignedByMeIsCreatedByMeForOthers() {
        val forDima = task(assignees = setOf(DIMA))
        val onlyMe = task(assignees = setOf(OLGA))
        val anyone = task()
        val byDima = task(createdBy = DIMA, assignees = setOf(OLGA))
        val rows = TaskViews.filter(build(listOf(forDima, onlyMe, anyone, byDima)), TaskFilter(TaskScope.ASSIGNED_BY_ME), OLGA)
        assertEquals(listOf(forDima.first), rows.map { it.id })
    }

    @Test
    fun tagAssigneeAndQueryFilters() {
        val car = task("Поменять резину", tagIds = setOf("tag-car"), assignees = setOf(DIMA))
        val trash = task("Вынести Мусор")
        val views = build(listOf(car, trash))
        assertEquals(listOf(car.first), TaskViews.filter(views, TaskFilter(tagId = "tag-car"), OLGA).map { it.id })
        assertEquals(listOf(car.first), TaskViews.filter(views, TaskFilter(assigneeId = DIMA), OLGA).map { it.id })
        assertEquals(listOf(trash.first), TaskViews.filter(views, TaskFilter(query = " мусор"), OLGA).map { it.id })
        assertEquals(emptyList(), TaskViews.filter(views, TaskFilter(query = "молоко"), OLGA))
    }

    @Test
    fun openListKeepsTasksDoneTodayAsTickedRows() {
        val doneToday = task("done today", due = today)
        val doneYesterday = task("done yesterday", due = today)
        val routine = task("routine", due = today, recurrence = daily)
        val open = task("open")
        val completions =
            listOf(
                completion(doneToday.first, now),
                completion(doneYesterday.first, Instant.parse("2026-10-20T10:00:00Z")),
                completion(routine.first, now, today),
            )
        val rows = TaskViews.filter(build(listOf(doneToday, doneYesterday, routine, open), completions), TaskFilter(), OLGA)
        val doneRows = rows.filter { it.isDoneTodayRow }
        assertEquals(setOf(doneToday.first, routine.first), doneRows.map { it.id }.toSet())
        assertTrue(doneRows.all { it.group == TaskGroup.TODAY })
        // The routine also keeps its open row for the next occurrence.
        val routineOpen = rows.single { it.id == routine.first && !it.isDoneTodayRow }
        assertEquals(TaskGroup.UPCOMING, routineOpen.group)
        assertNull(routineOpen.completedToday)
        assertFalse(rows.any { it.id == doneYesterday.first })
        assertTrue(rows.any { it.id == open.first })
    }

    @Test
    fun skipsAreNotDoneTodayRows() {
        val routine = task(due = today, recurrence = daily)
        val rows =
            TaskViews.filter(
                build(listOf(routine), listOf(completion(routine.first, now, today, skipped = true))),
                TaskFilter(),
                OLGA,
            )
        assertEquals(listOf(TaskGroup.UPCOMING), rows.map { it.group })
    }

    @Test
    fun doneListHasDoneOneOffsNewestFirst() {
        val first = task("first")
        val second = task("second")
        val routine = task(due = today, recurrence = daily)
        val open = task()
        val completions =
            listOf(
                completion(first.first, Instant.parse("2026-10-19T10:00:00Z")),
                completion(second.first, Instant.parse("2026-10-20T10:00:00Z")),
                completion(routine.first, now, today),
            )
        val rows = TaskViews.filter(build(listOf(first, second, routine, open), completions), TaskFilter(showDone = true), OLGA)
        assertEquals(listOf(second.first, first.first), rows.map { it.id })
        assertTrue(rows.all { it.group == TaskGroup.DONE })
    }

    @Test
    fun sectionsAreOrderedBySectionRules() {
        val overdueLate = task("overdue late", due = today, time = LocalTime(12, 0))
        val overdueEarly = task("overdue early", due = today.minusDays(2))
        val todayUntimed = task("today untimed", due = today)
        val todayLate = task("today 18", due = today, time = LocalTime(18, 0))
        val todayEarly = task("today 14", due = today, time = LocalTime(14, 0))
        val doneToday = task("done today", due = today)
        val upcomingFar = task("far", due = today.plusDays(5))
        val upcomingUntimed = task("tomorrow untimed", due = today.plusDays(1))
        val upcomingTimed = task("tomorrow 9", due = today.plusDays(1), time = LocalTime(9, 0))
        val noDate2 = task("no date 2")
        val noDate1 = task("no date 1")
        val all =
            listOf(
                noDate1,
                overdueLate,
                todayUntimed,
                upcomingFar,
                todayLate,
                doneToday,
                overdueEarly,
                upcomingUntimed,
                todayEarly,
                upcomingTimed,
                noDate2,
            ).shuffled(kotlin.random.Random(1))
        val rows = TaskViews.filter(build(all, listOf(completion(doneToday.first, now))), TaskFilter(), OLGA)
        val sections = TaskViews.sections(rows)
        assertEquals(listOf(TaskGroup.OVERDUE, TaskGroup.TODAY, TaskGroup.UPCOMING, TaskGroup.NO_DATE), sections.map { it.group })
        assertEquals(listOf("overdue early", "overdue late"), sections[0].tasks.map { it.data.title })
        assertEquals(listOf("today 14", "today 18", "today untimed", "done today"), sections[1].tasks.map { it.data.title })
        assertEquals(listOf("tomorrow 9", "tomorrow untimed", "far"), sections[2].tasks.map { it.data.title })
        assertEquals(listOf("no date 2", "no date 1"), sections[3].tasks.map { it.data.title })
    }

    @Test
    fun countsPerScopeIgnoreDoneTasks() {
        val mine = task(assignees = setOf(OLGA))
        val forDima = task(assignees = setOf(DIMA))
        val anyone = task()
        val done = task(assignees = setOf(OLGA))
        val views = build(listOf(mine, forDima, anyone, done), listOf(completion(done.first, now)))
        assertEquals(mapOf(TaskScope.ALL to 3, TaskScope.MINE to 2, TaskScope.ASSIGNED_BY_ME to 1), TaskViews.counts(views, OLGA))
    }

    @Test
    fun unknownAssigneesAndTagsAreDroppedArchivedTagsKept() {
        val t = task(assignees = setOf(DIMA, "gone-member"), tagIds = setOf("tag-old", "gone-tag"))
        val view = build(listOf(t)).single()
        assertEquals(listOf(DIMA), view.assignees.map { it.id })
        assertEquals(listOf("tag-old"), view.tags.map { it.id })
    }

    @Test
    fun childrenOfMissingTasksAreIgnoredAndRoutineChecklistsReset() {
        val routine = task(due = today.minusDays(1), recurrence = daily)
        val items =
            listOf(
                "i1" to ChecklistItemData(routine.first, "a", 0, done = true, doneOccurrence = today),
                "i2" to ChecklistItemData(routine.first, "b", 1, done = true, doneOccurrence = today.minusDays(1)),
                "orphan" to ChecklistItemData("missing-task", "c", 0, done = true),
            )
        val inputs = TaskInputs(listOf(routine), items, listOf(completion("missing-task", now)), members, tags, zone, me, now)
        val view = TaskViews.build(inputs).single()
        assertEquals(1 to 2, view.checklistDone to view.checklistTotal)
        val detail = TaskViews.detail(inputs, routine.first)!!
        assertEquals(listOf(true, false), detail.checklist.map { it.shownDone })
        assertEquals("Ольга", detail.creator?.name)
        assertNull(TaskViews.detail(inputs, "missing-task"))
    }

    @Test
    fun permissionsPerTask() {
        val own = task(assignees = setOf(DIMA))
        val dimasForDima = task(createdBy = DIMA, assignees = setOf(DIMA))
        val dimasAnyone = task(createdBy = DIMA)
        val views = build(listOf(own, dimasForDima, dimasAnyone)).associateBy { it.id }
        assertEquals(true to true, views.getValue(own.first).let { it.canEdit to it.canComplete })
        assertEquals(false to false, views.getValue(dimasForDima.first).let { it.canEdit to it.canComplete })
        assertEquals(false to true, views.getValue(dimasAnyone.first).let { it.canEdit to it.canComplete })
    }

    private fun LocalDate.minusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() - n)

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)
}
