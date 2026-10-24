package app.lares.client.ui

import app.lares.client.data.tasks.TasksRepository
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.main.TodayViewModel
import app.lares.client.ui.tasks.TaskEvent
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Today tasks block (TSK-023, FND-063). */
class TodayViewModelTest {
    private val today = TaskUiFixture.today

    private fun task(
        id: String,
        data: TaskData,
    ) = change(EntityTypes.TASK, id, TaskData.serializer(), data)

    private fun core(vararg extra: Pair<String, TaskData>) =
        FamilyFixture.core(FamilyFixture.olga, TaskUiFixture.changes + extra.map { (id, data) -> task(id, data) })

    @Test
    fun `my overdue tasks come first, then today, then done today`() {
        core(
            "t-mine-over" to TaskData("Renew insurance", "m2", dueDate = today.minus(2, DateTimeUnit.DAY), assigneeIds = setOf("m1")),
            "t-any-today" to TaskData("Water the plants", "m2", dueDate = today),
        ).use { core ->
            val vm = core.koin.get<TodayViewModel>()
            val state = awaitState(vm.state) { it.rows.size == 3 }
            assertTrue(state.showTasks)
            assertTrue(state.canCreate)
            // Boris's overdue task, Carol's routine and undated tasks are not in my day.
            assertEquals("t-mine-over", state.rows.first().key)
            assertEquals(
                setOf("t-today", "t-any-today"),
                state.rows
                    .drop(1)
                    .map { it.key }
                    .toSet(),
            )
            assertEquals(3, state.count)
            assertEquals(0, state.more)

            vm.toggle(state.rows.single { it.key == "t-today" })
            assertIs<TaskEvent.Completed>(TaskUiFixture.next(vm.events))
            val done = awaitState(vm.state) { it.count == 2 }
            assertEquals(listOf("t-mine-over", "t-any-today", "t-today"), done.rows.map { it.key }, "the done row goes last")
            assertTrue(done.rows.last().done)
        }
    }

    @Test
    fun `at most six rows and the rest as more`() {
        val extra = (1..7).map { "t-day-$it" to TaskData("Chore $it", "m1", dueDate = today, assigneeIds = setOf("m1")) }
        core(*extra.toTypedArray()).use { core ->
            val vm = core.koin.get<TodayViewModel>()
            val state = awaitState(vm.state) { it.count == 8 }
            assertEquals(TodayViewModel.MAX_ROWS, state.rows.size)
            assertEquals(2, state.more)
        }
    }

    @Test
    fun `hidden without tasks view`() {
        TaskUiFixture.core().use { core ->
            val inputs =
                runBlocking {
                    core.koin
                        .get<TasksRepository>()
                        .observeInputs()
                        .first { it.viewer != null }
                }
            val noTasks =
                inputs.copy(
                    viewer = inputs.viewer!!.copy(permissions = EffectivePermissions(false, setOf(Permission.SHOPPING_VIEW))),
                )
            val state = TodayViewModel.build(noTasks)
            assertTrue(state.loaded)
            assertFalse(state.showTasks)
            assertFalse(state.canCreate)
            assertTrue(state.rows.isEmpty())
        }
    }
}
