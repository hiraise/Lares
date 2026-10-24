package app.lares.contract.permissions

import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingListData

/**
 * Entity-level write rules of the shopping module (shopping spec § Permissions and privacy; architecture/shopping.md
 * § Write rules). Called by the server's sync handlers and by the client's adapters.
 */
object ShoppingRules {
    const val ADDED_BY = "addedBy"
    const val BOUGHT_BY = "boughtBy"

    /** Create, rename, tag, delete lists, toggle department grouping. */
    fun canManageLists(viewer: Viewer): Boolean = viewer.permissions.has(Permission.SHOPPING_MANAGE_LISTS)

    /** Add, change, tick off, remove items. */
    fun canEditItems(viewer: Viewer): Boolean = viewer.permissions.has(Permission.SHOPPING_EDIT)

    fun canCreateList(
        viewer: Viewer,
        list: ShoppingListData,
    ): Boolean = canManageLists(viewer) && list.createdBy == viewer.memberId

    /**
     * Author fields the write sets to someone else: [ADDED_BY] on create or when changed, [BOUGHT_BY] when changed to a
     * non-null member. Keeping another member's value is fine. A write with any foreign field is `FORBIDDEN`.
     */
    fun foreignAuthorFields(
        viewer: Viewer,
        existing: ShoppingItemData?,
        merged: ShoppingItemData,
    ): Set<String> =
        buildSet {
            val addedByChanged = existing == null || existing.addedBy != merged.addedBy
            if (addedByChanged && merged.addedBy != viewer.memberId) add(ADDED_BY)
            val boughtBy = merged.boughtBy
            if (boughtBy != null && boughtBy != existing?.boughtBy && boughtBy != viewer.memberId) add(BOUGHT_BY)
        }
}
