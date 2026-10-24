package app.lares.integration

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Shopping lists on several phones against the real server (plan M2-03): offline ticks, realtime, cascade, rights. */
class ShoppingSyncTest {
    private suspend fun TestClient.itemId(
        listId: String,
        name: String,
    ): String =
        snapshot()
            .shoppingItems.entries
            .single { it.value.listId == listId && it.value.name == name }
            .key

    private suspend fun TestClient.status(id: String) = snapshot().shoppingItems.getValue(id).status

    /** Anna's list «Groceries» with [names], synced to every phone. */
    private suspend fun Family.groceries(vararg names: String): String {
        val id = anna.shoppingWriter.createList("Groceries")
        anna.shoppingWriter.add(id, names.joinToString(", "))
        anna.sync()
        all.forEach { it.sync() }
        return id
    }

    @Test
    fun `SHP-004 two phones tick different items offline`() =
        familyTest {
            val list = groceries("Milk", "Bread")
            val milk = boris.itemId(list, "Milk")
            val bread = carol.itemId(list, "Bread")

            boris.network.online = false
            carol.network.online = false
            boris.shoppingWriter.setBought(milk, true)
            carol.shoppingWriter.setBought(bread, true)
            boris.network.online = true
            carol.network.online = true

            assertConverged(all)
            for (client in all) {
                assertEquals(ShoppingItemStatus.BOUGHT, client.status(milk))
                assertEquals(ShoppingItemStatus.BOUGHT, client.status(bread))
            }
            val items = anna.snapshot().shoppingItems
            assertEquals(borisMemberId, items.getValue(milk).boughtBy)
            assertEquals(carolMemberId, items.getValue(bread).boughtBy)
        }

    @Test
    fun `SHP-005 a phone in the foreground sees another phone's tick within 2 seconds`() =
        familyTest {
            val list = groceries("Milk")
            val milk = boris.itemId(list, "Milk")
            boris.engine.setForeground(true)
            boris.engine.realtimeConnected.await { it }

            anna.shoppingWriter.setBought(milk, true)
            anna.sync()
            boris.shopping.observeDetail(list).await(timeout = 2.seconds) { it?.bought?.singleOrNull()?.id == milk }

            boris.engine.setForeground(false)
            boris.engine.realtimeConnected.await { !it }
        }

    @Test
    fun `SHP-009 two phones bring back the same cleared item offline`() =
        familyTest {
            val list = groceries("Milk")
            val milk = anna.itemId(list, "Milk")
            anna.shoppingWriter.setBought(milk, true)
            anna.shoppingWriter.clearBought(list)
            anna.sync()
            all.forEach { it.sync() }

            boris.network.online = false
            carol.network.online = false
            boris.shoppingWriter.add(list, "milk 2 l")
            carol.shoppingWriter.add(list, "Milk")
            boris.network.online = true
            carol.network.online = true

            assertConverged(all)
            val items =
                anna
                    .snapshot()
                    .shoppingItems.values
                    .filter { it.listId == list }
            assertEquals(1, items.size)
            assertEquals(ShoppingItemStatus.NEEDED, items.single().status)
        }

    @Test
    fun `FND-040 a list deleted while another phone ticks offline is gone everywhere`() =
        familyTest {
            val list = groceries("Milk", "Bread")
            val milk = carol.itemId(list, "Milk")

            carol.network.online = false
            carol.shoppingWriter.setBought(milk, true)
            anna.shoppingWriter.deleteList(list)
            anna.sync()

            carol.network.online = true
            assertConverged(all)
            for (client in all) {
                val snapshot = client.snapshot()
                assertTrue(snapshot.shoppingLists.isEmpty() && snapshot.shoppingItems.isEmpty())
                assertEquals(emptyList(), client.shopping.observeLists().first())
            }
            assertEquals(
                ErrorCode.ENTITY_DELETED,
                carol.engine.rejections
                    .first()
                    .single()
                    .code,
            )
        }

    @Test
    fun `3 - a kid's list created through stale permissions is rejected and reverted`() =
        familyTest {
            anna.api.updateGroup(
                kidsId,
                UpdateGroupRequest(
                    permissions =
                        PermissionCatalogue.kidsTemplate + Permission.SHOPPING_MANAGE_LISTS,
                ),
            )
            carol.sync()
            carol.permissionsRepository.observeMine().await { it.has(Permission.SHOPPING_MANAGE_LISTS) }

            carol.network.online = false
            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate))
            val list = carol.shoppingWriter.createList("Toys")
            assertEquals(
                listOf(list),
                carol.shopping
                    .observeLists()
                    .first()
                    .map { it.id },
            )

            carol.network.online = true
            carol.sync()
            assertEquals(emptyList(), carol.shopping.observeLists().first())
            val rejection =
                carol.engine.rejections
                    .first()
                    .single()
            assertEquals(ErrorCode.FORBIDDEN, rejection.code)
            assertEquals(EntityTypes.SHOPPING_LIST, rejection.type)
            assertConverged(all)
        }

    @Test
    fun `SHP-001 a member without shopping view has no shopping rows after the reset`() =
        familyTest {
            groceries("Milk")
            assertEquals(1, carol.snapshot().shoppingLists.size)

            val guests = anna.api.createGroup(CreateGroupRequest("Guests", setOf(Permission.CALENDAR_VIEW))).groupId
            anna.api.setMemberGroups(carolMemberId, SetGroupsRequest(setOf(guests)))
            carol.sync()

            val snapshot = carol.snapshot()
            assertTrue(snapshot.shoppingLists.isEmpty() && snapshot.shoppingItems.isEmpty())
            assertEquals(emptyList(), carol.shopping.observeLists().first())
            assertEquals(1, anna.snapshot().shoppingLists.size)
        }

    @Test
    fun `SHP-006 a department correction reaches the same item of another list on other phones`() =
        familyTest {
            val groceries = groceries("Charcoal")
            val dacha = anna.shoppingWriter.createList("Dacha")
            anna.shoppingWriter.add(dacha, "charcoal")
            anna.sync()
            all.forEach { it.sync() }
            val inGroceries = boris.itemId(groceries, "Charcoal")
            val inDacha = boris.itemId(dacha, "Charcoal")

            boris.shoppingWriter.setCategory(inGroceries, ProductCategory.PETS)
            boris.sync()

            assertConverged(all)
            for (client in all) {
                val items = client.snapshot().shoppingItems
                assertEquals(ProductCategory.PETS, items.getValue(inGroceries).category)
                assertEquals(ProductCategory.PETS, items.getValue(inDacha).category)
            }
        }
}
