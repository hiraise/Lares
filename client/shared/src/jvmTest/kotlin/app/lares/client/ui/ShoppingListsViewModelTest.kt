package app.lares.client.ui

import app.lares.client.ui.shopping.ShoppingListsEvent
import app.lares.client.ui.shopping.ShoppingListsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShoppingListsViewModelTest {
    @Test
    fun `cards in creation order with counts, remaining and shopper`() {
        ShoppingUiFixture.core().use { core ->
            val vm = core.koin.get<ShoppingListsViewModel>()
            val state = awaitState(vm.state) { it.lists.size == 2 }
            assertEquals(listOf("Groceries", "Pharmacy"), state.lists.map { it.data.name })
            val groceries = state.lists[0]
            assertEquals(3, groceries.needed)
            assertEquals(1, groceries.bought)
            assertEquals(listOf("Apples", "Bread", "Milk"), groceries.remaining, "store order")
            assertEquals("m2", groceries.shopper?.id, "Boris ticked 5 minutes ago")
            assertEquals(0, state.lists[1].needed + state.lists[1].bought)
            assertTrue(state.canCreate)
        }
    }

    @Test
    fun `a kid sees the lists but cannot create one`() {
        ShoppingUiFixture.core(FamilyFixture.carol).use { core ->
            val vm = core.koin.get<ShoppingListsViewModel>()
            val state = awaitState(vm.state) { it.lists.size == 2 }
            assertFalse(state.canCreate)
        }
    }

    @Test
    fun `create a list and open it`() {
        ShoppingUiFixture.core().use { core ->
            val vm = core.koin.get<ShoppingListsViewModel>()
            awaitState(vm.state) { it.lists.size == 2 }
            vm.createList("  Hardware ")
            val created = assertIs<ShoppingListsEvent.Created>(TaskUiFixture.next(vm.events))
            val state = awaitState(vm.state) { it.lists.size == 3 }
            assertEquals(
                "Hardware",
                state.lists
                    .single { it.id == created.id }
                    .data.name,
            )
            assertEquals(listOf("shopping_list:${created.id}"), TaskUiFixture.outbox(core))
        }
    }
}
