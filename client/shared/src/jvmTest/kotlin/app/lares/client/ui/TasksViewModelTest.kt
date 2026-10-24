package app.lares.client.ui

import app.lares.client.data.tasks.TaskScope
import app.lares.client.ui.tasks.TaskEvent
import app.lares.client.ui.tasks.TasksUiState
import app.lares.client.ui.tasks.TasksViewModel
import app.lares.contract.tasks.TaskGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TasksViewModelTest {
    private fun TasksUiState.ids(group: TaskGroup) =
        sections
            .firstOrNull { it.group == group }
            ?.rows
            ?.map { it.key }
            .orEmpty()

    private fun TasksUiState.row(key: String) = sections.flatMap { it.rows }.single { it.key == key }

    @Test
    fun `default scope is Mine with chip counts and sections`() {
        TaskUiFixture.core().use { core ->
            val vm = core.koin.get<TasksViewModel>()
            val state = awaitState(vm.state) { it.counts[TaskScope.ALL] == 5 }
            assertEquals(TaskScope.MINE, state.filter.scope)
            assertEquals(mapOf(TaskScope.ALL to 5, TaskScope.MINE to 2, TaskScope.ASSIGNED_BY_ME to 3), state.counts)
            assertEquals(listOf(TaskGroup.TODAY, TaskGroup.NO_DATE), state.sections.map { it.group })
            assertEquals(listOf("t-today"), state.ids(TaskGroup.TODAY))
            assertEquals(listOf("t-any"), state.ids(TaskGroup.NO_DATE))
            assertTrue(state.canCreate)
            assertEquals(setOf("m1", "m2", "m3"), state.members.map { it.id }.toSet(), "everybody has tasks.view")

            vm.setScope(TaskScope.ALL)
            val all = awaitState(vm.state) { it.filter.scope == TaskScope.ALL }
            assertEquals(listOf(TaskGroup.OVERDUE, TaskGroup.TODAY, TaskGroup.UPCOMING, TaskGroup.NO_DATE), all.sections.map { it.group })
            assertEquals(listOf("t-over"), all.ids(TaskGroup.OVERDUE))
            assertEquals(listOf("t-routine", "t-today"), all.ids(TaskGroup.TODAY), "date-only rows by creation order")
            assertEquals(2, all.sections.single { it.group == TaskGroup.TODAY }.count)
        }
    }

    @Test
    fun `tag filter, search and reset`() {
        TaskUiFixture.core().use { core ->
            val vm = core.koin.get<TasksViewModel>()
            awaitState(vm.state) { it.loaded && it.counts.isNotEmpty() }
            vm.setScope(TaskScope.ALL)
            vm.setTag("t1")
            val tagged = awaitState(vm.state) { it.filter.tagId == "t1" && it.filter.scope == TaskScope.ALL }
            assertEquals(listOf("t-today"), tagged.sections.flatMap { it.rows }.map { it.key })
            assertEquals("Car", tagged.selectedTag?.data?.name)
            assertEquals(listOf("Car"), tagged.tags.map { it.data.name })
            assertTrue(tagged.filtered)

            vm.setTag(null)
            vm.toggleSearch()
            vm.setQuery("PLUMB")
            val found = awaitState(vm.state) { it.filter.query == "PLUMB" && it.filter.tagId == null && it.searchOpen }
            assertTrue(found.searchOpen)
            assertEquals(listOf("t-any"), found.sections.flatMap { it.rows }.map { it.key })

            vm.resetFilters()
            val reset = awaitState(vm.state) { it.filter.query.isEmpty() && it.filter.scope == TaskScope.MINE && !it.searchOpen }
            assertFalse(reset.searchOpen)
            assertFalse(reset.filtered)
        }
    }

    @Test
    fun `complete and undo from the list`() {
        TaskUiFixture.core().use { core ->
            val vm = core.koin.get<TasksViewModel>()
            val state = awaitState(vm.state) { it.counts[TaskScope.MINE] == 2 }
            vm.toggle(state.row("t-today"))
            val event = assertIs<TaskEvent.Completed>(TaskUiFixture.next(vm.events))
            val done = awaitState(vm.state) { it.counts[TaskScope.MINE] == 1 }
            val row = done.row("t-today")
            assertTrue(row.done && row.canToggle, "done today, still in Today, ticked")
            assertEquals(TaskGroup.TODAY, row.view.group)
            assertEquals(0, done.sections.single { it.group == TaskGroup.TODAY }.count, "done rows are not counted")
            assertTrue("task_completion:${event.completionId}" in TaskUiFixture.outbox(core))

            vm.undo(event.completionId)
            val undone = awaitState(vm.state) { it.counts[TaskScope.MINE] == 2 }
            assertFalse(undone.row("t-today").done)
        }
    }

    @Test
    fun `a kid cannot tick an adult's task`() {
        TaskUiFixture.core(FamilyFixture.carol).use { core ->
            val vm = core.koin.get<TasksViewModel>()
            awaitState(vm.state) { it.counts[TaskScope.MINE] == 3 }
            vm.setScope(TaskScope.ALL)
            val all = awaitState(vm.state) { it.filter.scope == TaskScope.ALL && it.sections.isNotEmpty() }
            val adults = all.row("t-over")
            assertFalse(adults.canToggle)
            assertTrue(all.row("t-kid").canToggle)
            assertTrue(all.canCreate, "Kids may create tasks")
            val before = TaskUiFixture.outbox(core)

            vm.toggle(adults)
            assertEquals(TaskEvent.NotAllowed, TaskUiFixture.next(vm.events))
            assertEquals(before, TaskUiFixture.outbox(core), "nothing enqueued")
        }
    }
}
