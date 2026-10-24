package app.lares.client.ui

import app.lares.client.ui.FamilyFixture.change
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Shopping lists of the [FamilyFixture] household, timed relative to the real clock:
 * - `l1` "Groceries" (by Olga, grouped): Apples (produce, 1 kg), Bread (bakery, tag `t1` "Car"), Milk (dairy, 2 l,
 *   "Lactose free", by Boris) to buy; Eggs bought by Boris 5 minutes ago (so Boris is shopping); Cheese cleared;
 * - `l2` "Pharmacy": nothing to buy; Mouthwash (personal care) and Bread (bakery) cleared.
 */
object ShoppingUiFixture {
    private val now = Clock.System.now()

    val changes =
        listOf(
            list("l1", ShoppingListData("Groceries", "m1")),
            list("l2", ShoppingListData("Pharmacy", "m1")),
            item("i-apples", "Apples", ProductCategory.PRODUCE, quantity = "1 kg", addedAgo = 30),
            item("i-bread", "Bread", ProductCategory.BAKERY, addedAgo = 29, tagIds = setOf("t1")),
            item("i-milk", "Milk", ProductCategory.DAIRY, quantity = "2 l", note = "Lactose free", addedBy = "m2", addedAgo = 28),
            item("i-eggs", "Eggs", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtBy = "m2", boughtAgo = 5),
            item("i-cheese", "Cheese", ProductCategory.DAIRY, ShoppingItemStatus.CLEARED, boughtBy = "m1", boughtAgo = 600),
            item("i-mouthwash", "Mouthwash", ProductCategory.PERSONAL_CARE, ShoppingItemStatus.CLEARED, listId = "l2"),
            item("i-bread2", "Bread", ProductCategory.BAKERY, ShoppingItemStatus.CLEARED, listId = "l2"),
        )

    /** Kids without `shopping.edit`: Carol may only look. */
    private val viewOnlyKids =
        change(
            EntityTypes.GROUP,
            FamilyFixture.KIDS,
            GroupData.serializer(),
            GroupData("Kids", null, PermissionCatalogue.kidsTemplate - Permission.SHOPPING_EDIT, setOf("m3")),
        )

    private fun list(
        id: String,
        data: ShoppingListData,
    ) = change(EntityTypes.SHOPPING_LIST, id, ShoppingListData.serializer(), data)

    private fun item(
        id: String,
        name: String,
        category: ProductCategory,
        status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
        quantity: String? = null,
        note: String? = null,
        addedBy: String = "m1",
        addedAgo: Int = 60,
        boughtBy: String? = null,
        boughtAgo: Int? = null,
        tagIds: Set<String> = emptySet(),
        listId: String = "l1",
    ) = change(
        EntityTypes.SHOPPING_ITEM,
        id,
        ShoppingItemData.serializer(),
        ShoppingItemData(
            listId = listId,
            name = name,
            addedBy = addedBy,
            addedAt = now - addedAgo.minutes,
            status = status,
            category = category,
            quantity = quantity,
            note = note,
            tagIds = tagIds,
            boughtBy = boughtBy,
            boughtAt = boughtAgo?.let { now - it.minutes },
            boughtCount = if (boughtBy != null) 1 else 0,
        ),
    )

    fun core(
        me: Me = FamilyFixture.olga,
        viewOnly: Boolean = false,
        extra: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
    ): UiTestCore = FamilyFixture.core(me, if (viewOnly) changes + viewOnlyKids else changes, extra)
}
