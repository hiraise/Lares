package app.lares.contract.permissions

import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The permission table of the shopping spec for admin, Adults, Kids and a custom "view only" group. */
class ShoppingRulesTest {
    private val admin = EffectivePermissions(isAdmin = true, permissions = emptySet())
    private val adults = EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.adultsTemplate)
    private val kids =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate))
    private val viewOnly = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.SHOPPING_VIEW))

    /** role → (manage lists, edit items) */
    private val expected =
        mapOf(
            "admin" to (admin to (true to true)),
            "adults" to (adults to (true to true)),
            "kids" to (kids to (false to true)),
            "view only" to (viewOnly to (false to false)),
        )

    private fun viewer(
        member: String,
        permissions: EffectivePermissions,
    ) = Viewer(member, "p-$member", permissions)

    @Test
    fun `SHP-001 lists are managed with shopping manage lists, created only as oneself`() {
        for ((role, entry) in expected) {
            val (permissions, rights) = entry
            val me = viewer("me", permissions)
            assertEquals(rights.first, ShoppingRules.canManageLists(me), "$role manage")
            assertEquals(rights.first, ShoppingRules.canCreateList(me, ShoppingListData("Продукты", "me")), "$role create own")
            assertEquals(false, ShoppingRules.canCreateList(me, ShoppingListData("Продукты", "other")), "$role create foreign")
        }
    }

    @Test
    fun `SHP-002 SHP-004 items are edited with shopping edit`() {
        for ((role, entry) in expected) {
            val (permissions, rights) = entry
            assertEquals(rights.second, ShoppingRules.canEditItems(viewer("me", permissions)), role)
        }
    }

    private val at = Instant.parse("2026-10-24T10:00:00Z")
    private val item = ShoppingItemData("l1", "Хлеб", addedBy = "other", addedAt = at)
    private val me = viewer("me", adults)

    @Test
    fun `foreign author fields on create`() {
        assertEquals(emptySet(), ShoppingRules.foreignAuthorFields(me, null, item.copy(addedBy = "me")))
        assertEquals(setOf(ShoppingRules.ADDED_BY), ShoppingRules.foreignAuthorFields(me, null, item))
        assertEquals(
            setOf("addedBy", "boughtBy"),
            ShoppingRules.foreignAuthorFields(me, null, item.copy(status = ShoppingItemStatus.BOUGHT, boughtBy = "other", boughtAt = at)),
        )
    }

    @Test
    fun `foreign author fields on change`() {
        val tickedByMe = item.copy(status = ShoppingItemStatus.BOUGHT, boughtBy = "me", boughtAt = at, boughtCount = 1)
        assertEquals(emptySet(), ShoppingRules.foreignAuthorFields(me, item, tickedByMe), "tick by me keeps the other's addedBy")
        val boughtByOther = item.copy(status = ShoppingItemStatus.BOUGHT, boughtBy = "other", boughtAt = at)
        assertEquals(emptySet(), ShoppingRules.foreignAuthorFields(me, boughtByOther, boughtByOther.copy(quantity = "2")))
        assertEquals(
            emptySet(),
            ShoppingRules.foreignAuthorFields(me, boughtByOther, boughtByOther.copy(status = ShoppingItemStatus.CLEARED)),
        )
        val readdedByMe = boughtByOther.copy(status = ShoppingItemStatus.NEEDED, addedBy = "me", addedAt = at)
        assertEquals(emptySet(), ShoppingRules.foreignAuthorFields(me, boughtByOther, readdedByMe), "re-add by me")
        assertEquals(setOf("boughtBy"), ShoppingRules.foreignAuthorFields(me, tickedByMe, tickedByMe.copy(boughtBy = "third")))
        assertEquals(
            setOf("addedBy"),
            ShoppingRules.foreignAuthorFields(me, item.copy(addedBy = "me"), item.copy(addedBy = "third")),
        )
        assertEquals(emptySet(), ShoppingRules.foreignAuthorFields(me, tickedByMe, tickedByMe.copy(boughtBy = null)), "untick")
    }
}
