package app.lares.client.ui

import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.db.LocalDb
import app.lares.client.resources.Res
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.resources.shopping_offline_stale
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.uiText
import app.lares.client.ui.shopping.ItemPicker
import app.lares.client.ui.shopping.ShoppingEvent
import app.lares.client.ui.shopping.ShoppingListArgs
import app.lares.client.ui.shopping.ShoppingListDialog
import app.lares.client.ui.shopping.ShoppingListUiState
import app.lares.client.ui.shopping.ShoppingListViewModel
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.serialization.json.jsonObject
import org.koin.core.parameter.parametersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    private fun ShoppingListUiState.toBuy() =
        detail
            ?.sections
            ?.flatMap { it.items }
            ?.map { it.data.name }
            .orEmpty()

    /** Field names of the pending upserts of [id]. */
    private fun outboxFields(
        core: UiTestCore,
        id: String,
    ): Set<String> =
        runBlocking {
            core.koin.get<LocalDb>().read {
                sync_outboxQueries
                    .peek(100)
                    .executeAsList()
                    .filter { it.entity_id == id }
                    .flatMap { row -> row.fields?.let { LaresJson.parseToJsonElement(it).jsonObject.keys }.orEmpty() }
                    .toSet()
            }
        }

    /** The local row [id], from any list. */
    private fun row(
        core: UiTestCore,
        id: String,
    ) = runBlocking {
        core.koin
            .get<ShoppingRepository>()
            .observeInputs()
            .first()
            .items
            .single { it.first == id }
            .second
    }

    @Test
    fun `quick add puts items in their departments and reports those already there`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.add("Bananas, Milk 1 l")
            val event = assertIs<ShoppingEvent.AlreadyThere>(TaskUiFixture.next(vm.events))
            assertEquals(listOf("Milk"), event.names)
            // Each entry is its own write: wait for both.
            val state =
                awaitState(vm.state) {
                    "Bananas" in it.names(ProductCategory.PRODUCE) && it.item("Milk").data.quantity == "1 l"
                }
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

    @Test
    fun `the item sheet saves only the changed fields`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            vm.openItem(loaded.item("Milk"))
            val sheet = awaitState(vm.state) { it.sheet != null }.sheet!!
            assertEquals("Lactose free", sheet.form.note)
            assertFalse(sheet.canSave, "nothing changed yet")
            vm.editItem { it.copy(quantity = " 1 l ", note = "") }
            assertTrue(awaitState(vm.state) { it.sheet?.changed == true }.sheet!!.canSave)
            vm.saveItem()
            val saved = awaitState(vm.state) { it.sheet == null && it.item("Milk").data.quantity == "1 l" }
            assertNull(saved.item("Milk").data.note)
            assertEquals(setOf("quantity", "note"), outboxFields(core, "i-milk"))
        }
    }

    @Test
    fun `a department change is remembered for the name in other lists`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            vm.openItem(loaded.item("Bread"))
            awaitState(vm.state) { it.sheet != null }
            vm.openPicker(ItemPicker.DEPARTMENT)
            assertEquals(ItemPicker.DEPARTMENT, awaitState(vm.state) { it.sheet?.picker != null }.sheet!!.picker)
            vm.onDepartment(ProductCategory.OTHER)
            val picked = awaitState(vm.state) { it.sheet?.form?.category == ProductCategory.OTHER }
            assertNull(picked.sheet!!.picker, "the choice closes the dialog")
            vm.saveItem()
            awaitState(vm.state) { "Bread" in it.names(ProductCategory.OTHER) }
            assertEquals(ProductCategory.OTHER, row(core, "i-bread2").category, "the same name in Pharmacy")
        }
    }

    @Test
    fun `remove from the list and undo`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            vm.openItem(loaded.item("Milk"))
            awaitState(vm.state) { it.sheet != null }
            vm.removeItem()
            val event = assertIs<ShoppingEvent.Removed>(TaskUiFixture.next(vm.events))
            assertEquals(ShoppingEvent.Removed("i-milk", ShoppingItemStatus.NEEDED), event)
            val removed = awaitState(vm.state) { "Milk" !in it.toBuy() }
            assertNull(removed.sheet)
            assertTrue(removed.detail!!.suggestions.any { it.data.name == "Milk" }, "kept as a suggestion")
            vm.undoRemove(event.id, event.previous)
            awaitState(vm.state) { "Milk" in it.names(ProductCategory.DAIRY) }
        }
    }

    @Test
    fun `a suggestion tap brings the item back and forget removes the chip`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            val cheese = loaded.detail!!.suggestions.single()
            assertEquals("Cheese", cheese.data.name)
            vm.addSuggestion(cheese)
            val back = awaitState(vm.state) { "Cheese" in it.names(ProductCategory.DAIRY) }
            assertTrue(back.detail!!.suggestions.isEmpty())

            vm.toggle(back.item("Cheese"))
            awaitState(vm.state) { "Cheese" in it.bought() }
            vm.clearBought()
            assertIs<ShoppingEvent.Cleared>(TaskUiFixture.next(vm.events))
            val chips = awaitState(vm.state) { it.detail!!.suggestions.size == 2 }
            vm.askForget(chips.detail!!.suggestions.first { it.data.name == "Cheese" })
            assertEquals("Cheese", awaitState(vm.state) { it.forget != null }.forget!!.data.name)
            vm.forget()
            val forgotten = awaitState(vm.state) { it.detail!!.suggestions.none { s -> s.data.name == "Cheese" } }
            assertNull(forgotten.forget)
            assertEquals(listOf("Eggs"), forgotten.detail!!.suggestions.map { it.data.name })
        }
    }

    @Test
    fun `autocomplete offers a name from another list and adds it with its department`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.onQuery("m")
            vm.onQuery("mo")
            val found = awaitState(vm.state) { it.autocomplete.isNotEmpty() }.autocomplete
            assertEquals(listOf("Mouthwash"), found.map { it.name })
            assertNull(found.single().itemId, "a new row in this list")
            vm.addSuggestion(found.single())
            awaitState(vm.state) { "Mouthwash" in it.names(ProductCategory.PERSONAL_CARE) }
            vm.onQuery("")
            assertTrue(awaitState(vm.state) { it.autocomplete.isEmpty() }.autocomplete.isEmpty())
        }
    }

    @Test
    fun `the tag filter narrows the items and tags what is added`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            assertEquals(listOf("t1"), loaded.detail!!.tagsInUse.map { it.id })
            vm.filterBy("t1")
            val filtered = awaitState(vm.state) { it.tagId == "t1" }
            assertEquals(listOf("Bread"), filtered.toBuy())
            assertEquals(1, filtered.detail!!.list.needed, "counts follow the filter")
            vm.add("Paper")
            val added = awaitState(vm.state) { "Paper" in it.toBuy() }
            assertEquals(setOf("t1"), added.item("Paper").data.tagIds)
            vm.filterBy(null)
            assertEquals(listOf("Apples", "Bread", "Milk", "Paper"), awaitState(vm.state) { it.tagId == null }.toBuy().sorted())
        }
    }

    @Test
    fun `list tags are saved when the sheet closes`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            awaitState(vm.state) { it.detail != null }
            vm.openListTags()
            assertEquals(emptySet(), awaitState(vm.state) { it.listTags != null }.listTags)
            vm.toggleListTag("t1")
            awaitState(vm.state) { it.listTags == setOf("t1") }
            vm.closeListTags()
            val saved =
                awaitState(vm.state) {
                    it.detail!!
                        .list.tags
                        .isNotEmpty()
                }
            assertNull(saved.listTags)
            assertEquals(
                listOf("Car"),
                saved.detail!!
                    .list.tags
                    .map { it.data.name },
            )
        }
    }

    @Test
    fun `closing the sheet with changes asks first`() {
        ShoppingUiFixture.core().use { core ->
            val vm = vm(core)
            val loaded = awaitState(vm.state) { it.detail != null }
            vm.openItem(loaded.item("Apples"))
            awaitState(vm.state) { it.sheet != null }
            vm.requestCloseItem()
            awaitState(vm.state) { it.sheet == null }

            vm.openItem(loaded.item("Apples"))
            awaitState(vm.state) { it.sheet != null }
            vm.editItem { it.copy(name = "Green apples") }
            awaitState(vm.state) { it.sheet?.changed == true }
            vm.requestCloseItem()
            assertTrue(awaitState(vm.state) { it.sheet?.confirmDiscard == true }.sheet!!.changed)
            vm.keepEditing()
            assertEquals("Green apples", awaitState(vm.state) { it.sheet?.confirmDiscard == false }.sheet!!.form.name)
            vm.requestCloseItem()
            awaitState(vm.state) { it.sheet?.confirmDiscard == true }
            vm.closeItem()
            assertNotNull(awaitState(vm.state) { it.sheet == null }.detail)
            assertEquals(emptyList(), TaskUiFixture.outbox(core), "nothing saved")
        }
    }

    @Test
    fun `a view-only member cannot open the item sheet`() {
        ShoppingUiFixture.core(FamilyFixture.carol, viewOnly = true).use { core ->
            val vm = vm(core)
            val state = awaitState(vm.state) { it.detail != null }
            vm.openItem(state.item("Bread"))
            vm.onQuery("mo")
            vm.toggleBought()
            val after = awaitState(vm.state) { it.boughtExpanded }
            assertNull(after.sheet)
            assertTrue(after.autocomplete.isEmpty())
        }
    }
}
