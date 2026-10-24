package app.lares.client.data.shopping

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.Viewer
import kotlin.time.Instant

// What the shopping repository exposes (architecture/shopping.md § Models and views): ready-to-show views computed
// from the synced rows.

/** Everything the views are computed from; [now] comes from the minute ticker. */
data class ShoppingInputs(
    val lists: List<Pair<String, ShoppingListData>>,
    val items: List<Pair<String, ShoppingItemData>>,
    val members: List<MemberView>,
    /** Archived tags included. */
    val tags: List<Tag>,
    val viewer: Viewer?,
    val now: Instant,
)

data class ShoppingItemView(
    val id: String,
    val data: ShoppingItemData,
    /** Unknown or deleted tag ids are dropped; archived tags are kept. */
    val tags: List<Tag>,
    val addedBy: MemberView?,
    val boughtBy: MemberView?,
    /** Added by the logged-in member. */
    val mine: Boolean,
)

data class ShoppingListView(
    val id: String,
    val data: ShoppingListData,
    val needed: Int,
    val bought: Int,
    /** The first [ShoppingViews.REMAINING_MAX] names to buy, in display order. */
    val remaining: List<String>,
    /** Who is shopping right now (SHP-012). */
    val shopper: MemberView?,
    /** The latest `addedAt` / `boughtAt` of its items. */
    val lastActivity: Instant?,
    val tags: List<Tag>,
)

/** Items to buy of one department; [category] is null when the list is not grouped. */
data class DepartmentSection(
    val category: ProductCategory?,
    val items: List<ShoppingItemView>,
)

data class ShoppingListDetail(
    val list: ShoppingListView,
    val sections: List<DepartmentSection>,
    /** Newest tick first. */
    val bought: List<ShoppingItemView>,
    val suggestions: List<ShoppingItemView>,
    /** Tags on the list's items to buy or bought (the tag filter's chips). */
    val tagsInUse: List<Tag>,
    val canEdit: Boolean,
    val canManage: Boolean,
)

/** An autocomplete entry: [itemId] is a row of this list to bring back, or null for a new row. */
data class Suggestion(
    val name: String,
    val category: ProductCategory,
    val note: String?,
    val itemId: String?,
)

/** The list for the Today card and how many other lists have something to buy. */
data class ActiveList(
    val list: ShoppingListView,
    val othersToBuy: Int,
)

/** The shopping section of a tag page: items to buy with the tag (with their list's name) and lists with the tag. */
data class ShoppingTagView(
    val items: List<Pair<ShoppingItemView, String>>,
    val lists: List<ShoppingListView>,
)

/** Outcome of a quick add: rows created or brought back, and names that were already in the list to buy. */
data class AddResult(
    val added: Int,
    val alreadyThere: List<String>,
)
