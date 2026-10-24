package app.lares.client.ui

import app.lares.client.resources.Res
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.resources.shopping_offline_stale
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.uiText
import app.lares.client.ui.shopping.ShoppingEvent
import app.lares.client.ui.shopping.ShoppingListArgs
import app.lares.client.ui.shopping.ShoppingListDialog
import app.lares.client.ui.shopping.ShoppingListUiState
import app.lares.client.ui.shopping.ShoppingListViewModel
import app.lares.contract.entities.ProductCategory
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingListViewModelTest {
    private fun vm(core: UiTestCore) = core.koin.get<ShoppingListViewModel> { parametersOf(ShoppingListArgs("l1")) }

    private fun ShoppingListUiState.names(category: ProductCategory?) =
        detail
            ?.sections
            ?.firstOrNull { it.category == category }
            ?.items
            ?.map { it.data.name }
            .orEmpty()

    private fun ShoppingListUiState.item(name: String) =
        detail!!.sections.flatMap { it.items }.firstOrNull { it.data.name == name }
            ?: detail.bought.single { it.data.name == name }

    private fun ShoppingListUiState.bought() = detail?.bought?.map { it.data.name }.orEmpty()

    @Test
    fun `quick add puts items in their departments and reports those already there`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.add("Bananas, Milk 1 l")
            val event = assertIs<ShoppingEvent.AlreadyThere>(TaskUiFixture.next(vm.events))
            assertEquals(listOf("Milk"), event.names)
            val state = awaitState(vm.state) { "Bananas" in it.names(ProductCategory.PRODUCE) }
            assertEquals(listOf("Apples", "Bananas"), state.names(ProductCategory.PRODUCE))
            assertEquals("1 l", state.item("Milk").data.quantity, "a new quantity replaces the old one")
            assertEquals(listOf("Milk"), state.names(ProductCategory.DAIRY), "no duplicate")
        }
    }

    @Test
    fun `tick and untick`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            vm.toggle(loaded.item("Apples"))
            val ticked = awaitState(vm.state) { "Apples" in it.bought() }
            assertEquals(listOf("Apples", "Eggs"), ticked.bought(), "newest tick first")
            assertEquals(2, ticked.detail!!.list.bought)
            vm.toggle(ticked.item("Apples"))
            val back = awaitState(vm.state) { "Apples" !in it.bought() }
            assertEquals(listOf("Apples"), back.names(ProductCategory.PRODUCE))
        }
    }

    @Test
    fun `clear bought and undo`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.clearBought()
            val event = assertIs<ShoppingEvent.Cleared>(TaskUiFixture.next(vm.events))
            assertEquals(listOf("i-eggs"), event.ids)
            awaitState(vm.state) { it.bought().isEmpty() }
            vm.undoClear(event.ids)
            awaitState(vm.state) { it.bought() == listOf("Eggs") }
        }
    }

    @Test
    fun `rename, grouping toggle and the bought section switch`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.openDialog(ShoppingListDialog.RENAME)
            awaitState(vm.state) { it.dialog == ShoppingListDialog.RENAME }
            vm.rename(" Food ")
            val renamed =
                awaitState(vm.state) {
                    it.detail
                        ?.list
                        ?.data
                        ?.name == "Food"
                }
            assertNull(renamed.dialog)

            vm.toggleGrouping()
            val flat =
                awaitState(vm.state) {
                    it.detail
                        ?.list
                        ?.data
                        ?.groupByCategory == false
                }
            assertEquals(listOf<ProductCategory?>(null), flat.detail!!.sections.map { it.category })
            assertEquals(listOf("Apples", "Bread", "Milk"), flat.names(null))

            vm.toggleBought()
            assertTrue(awaitState(vm.state) { it.boughtExpanded }.boughtExpanded)
        }
    }

    @Test
    fun `delete closes the page`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.delete()
            assertTrue(awaitState(vm.state) { it.gone }.gone)
            assertEquals(listOf("shopping_list:l1"), TaskUiFixture.outbox(core))
        }
    }

    @Test
    fun `offline banner without and with changes waiting`() {
        val offline = AtomicBoolean(false)
        ShoppingUiFixture.core(extra = { if (offline.get()) throw IOException("no network") else null }).use { core ->
            val vm = vm(core)
            val online = awaitState(vm.state) { it.detail != null }
            assertNull(online.banner)

            offline.set(true)
            runBlocking { core.koin.get<SyncEngine>().syncNow() }
            awaitState(vm.state) { it.banner == uiText(Res.string.shopping_offline_stale) }

            vm.toggle(online.item("Bread"))
            awaitState(vm.state) { it.banner == uiText(Res.string.shopping_offline_pending) }
        }
    }

    @Test
    fun `a view-only member cannot tick`() {
        ShoppingUiFixture.core(FamilyFixture.carol, viewOnly = true).use { core ->
            val vm = vm(core)
            val state = awaitState(vm.state) { it.detail != null }
            assertEquals(false, state.detail!!.canEdit)
            vm.toggle(state.item("Bread"))
            assertEquals(ShoppingEvent.NotAllowed, TaskUiFixture.next(vm.events))
            vm.add("Paper")
            assertEquals(ShoppingEvent.NotAllowed, TaskUiFixture.next(vm.events))
            assertEquals(emptyList(), TaskUiFixture.outbox(core))
        }
    }
}
