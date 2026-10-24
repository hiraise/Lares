package app.lares.client.ui

import app.lares.client.ui.tasks.TaskDetailArgs
import app.lares.client.ui.tasks.TaskDetailViewModel
import app.lares.client.ui.tasks.TaskEvent
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDetailViewModelTest {
    private fun UiTestCore.detail(id: String) = koin.get<TaskDetailViewModel> { parametersOf(TaskDetailArgs(id)) }

    @Test
    fun `routine history is newest first and only the latest entry can be undone`() {
        TaskUiFixture.core().use { core ->
            val vm = core.detail("t-routine")
            val state = awaitState(vm.state) { it.detail != null }
            assertEquals(listOf("c-new", "c-old"), state.history.map { it.completion.id })
            assertEquals(listOf(true, false), state.history.map { it.canUndo })
            assertEquals("Olga", state.detail?.creator?.name)
            assertFalse(state.canReopen)

            val oneOff = awaitState(core.detail("t-today").state) { it.detail != null }
            assertTrue(oneOff.history.isEmpty(), "no history section for one-off tasks")
        }
    }

    @Test
    fun `checklist add, tick, rename and delete`() {
        TaskUiFixture.core().use { core ->
            val vm = core.detail("t-routine")
            awaitState(vm.state) { it.detail?.checklist?.size == 2 }

            vm.openNewItem()
            vm.onNewItem("  Recycling ")
            vm.addItem()
            val added = awaitState(vm.state) { it.detail?.checklist?.size == 3 }
            assertEquals(listOf("Kitchen bin", "Bathroom bin", "Recycling"), added.detail!!.checklist.map { it.data.text })
            assertEquals("", vm.newItem.value, "the field stays open for the next item")

            vm.toggleItem(added.detail!!.checklist.first())
            awaitState(vm.state) {
                it.detail
                    ?.checklist
                    ?.first()
                    ?.shownDone == true
            }

            vm.renameItem("i2", "Bath bin")
            awaitState(vm.state) { it.detail?.checklist?.any { item -> item.data.text == "Bath bin" } == true }

            vm.deleteItem("i1")
            val after = awaitState(vm.state) { it.detail?.checklist?.size == 2 }
            assertEquals(listOf("Bath bin", "Recycling"), after.detail!!.checklist.map { it.data.text })

            vm.closeNewItem()
            assertNull(vm.newItem.value)
        }
    }

    @Test
    fun `skip an occurrence`() {
        TaskUiFixture.core().use { core ->
            val vm = core.detail("t-routine")
            val before = awaitState(vm.state) { it.detail != null }
            val occurrence =
                before.detail!!
                    .task.state.occurrence!!
            vm.skip()
            assertEquals(TaskEvent.Skipped, TaskUiFixture.next(vm.events))
            val after = awaitState(vm.state) { it.history.size == 3 }
            val latest = after.history.first()
            assertTrue(latest.completion.data.skipped && latest.canUndo)
            assertEquals("m1", latest.completion.data.completedBy)
            assertEquals(occurrence, latest.completion.data.occurrence)
            assertTrue(
                after.detail!!
                    .task.state.occurrence!! > occurrence,
                "the next occurrence is current",
            )
        }
    }

    @Test
    fun `delete the whole series closes the page`() {
        TaskUiFixture.core().use { core ->
            val vm = core.detail("t-routine")
            awaitState(vm.state) { it.detail != null }
            vm.delete()
            awaitState(vm.state) { it.gone }
            assertTrue("task:t-routine" in TaskUiFixture.outbox(core))
        }
    }
}
