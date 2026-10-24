package app.lares.client.ui

import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.family.TagArgs
import app.lares.client.ui.family.TagViewModel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskData
import app.lares.contract.sync.EntityTypes
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tag page, tasks section (TAG-004). */
class TagViewModelTest {
    private fun core() =
        FamilyFixture.core(
            FamilyFixture.olga,
            TaskUiFixture.changes +
                listOf(
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
}
