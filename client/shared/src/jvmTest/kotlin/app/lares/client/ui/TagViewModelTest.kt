package app.lares.client.ui

import app.lares.client.data.tasks.TasksRepository
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.family.TagArgs
import app.lares.client.ui.family.TagViewModel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tag page, tasks and shopping sections (TAG-004, SHP-014). */
class TagViewModelTest {
    private fun core() =
        FamilyFixture.core(
            FamilyFixture.olga,
            TaskUiFixture.changes + ShoppingUiFixture.changes +
                listOf(
                    change(EntityTypes.SHOPPING_LIST, "l3", ShoppingListData.serializer(), ShoppingListData("Trip", "m1", setOf("t1"))),
                    change(EntityTypes.TAG, "t2", TagData.serializer(), TagData("Dacha", PaletteColor.GREEN, archived = true)),
                    change(EntityTypes.TASK, "t-car", TaskData.serializer(), TaskData("Change tyres", "m2", tagIds = setOf("t1"))),
                    change(EntityTypes.TASK, "t-dacha", TaskData.serializer(), TaskData("Close the water", "m1", tagIds = setOf("t2"))),
                ),
        )

    private fun UiTestCore.page(id: String) = koin.get<TagViewModel> { parametersOf(TagArgs(id)) }

    @Test
    fun `lists open tasks with the tag only, ordered like All`() {
        core().use { core ->
            val state = awaitState(core.page("t1").state) { it.loaded && it.tasks.size == 2 }
            assertEquals("Car", state.tag?.data?.name)
            assertEquals(listOf("t-today", "t-car"), state.tasks.map { it.key }, "today before no date")
            assertTrue(state.canManage)
        }
    }

    @Test
    fun `an archived tag still shows its tasks`() {
        core().use { core ->
            val state = awaitState(core.page("t2").state) { it.loaded && it.tag != null }
            assertTrue(state.tag!!.data.archived)
            assertEquals(listOf("t-dacha"), state.tasks.map { it.key })
        }
    }

    @Test
    fun `a deleted tag closes the page`() {
        core().use { core ->
            assertTrue(awaitState(core.page("gone").state) { it.loaded }.gone)
        }
    }

    @Test
    fun `shopping items to buy and lists with the tag`() {
        core().use { core ->
            val state = awaitState(core.page("t1").state) { it.loaded && it.hasShopping }
            assertEquals(listOf("i-bread" to "Groceries"), state.shoppingItems.map { (item, list) -> item.id to list })
            assertEquals(listOf("l3"), state.shoppingLists.map { it.id })
            assertEquals(0, state.shoppingLists.single().needed)
        }
    }

    @Test
    fun `no shopping section without rows`() {
        core().use { core ->
            val state = awaitState(core.page("t2").state) { it.loaded && it.tasks.isNotEmpty() }
            assertFalse(state.hasShopping)
            assertTrue(state.shoppingItems.isEmpty() && state.shoppingLists.isEmpty())
        }
    }

    @Test
    fun `events with the tag, only with calendar view (TAG-004)`() {
        CalendarUiFixture.core().use { core ->
            val state = awaitState(core.page("t1").state) { it.loaded && it.events.isNotEmpty() }
            assertEquals(listOf(CalendarUiFixture.E_HOLIDAY), state.events.map { it.occ.eventId })
            assertEquals(CalendarUiFixture.today, state.today)
            val inputs =
                runBlocking {
                    core.koin
                        .get<TasksRepository>()
                        .observeInputs()
                        .first { it.viewer != null }
                }
            val noCalendar =
                inputs.copy(
                    viewer = inputs.viewer!!.copy(permissions = EffectivePermissions(false, setOf(Permission.TASKS_VIEW))),
                )
            assertTrue(TagViewModel.build(noCalendar, "t1", null, state.events).events.isEmpty())
        }
    }
}
