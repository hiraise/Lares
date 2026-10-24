package app.lares.client.data.shopping

import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.ShoppingRules
import app.lares.contract.shopping.ShoppingNames
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pure view functions of the shopping module (architecture/shopping.md § Models and views). Items whose list is not in
 * the inputs are ignored everywhere; unknown member and tag ids are dropped.
 */
object ShoppingViews {
    const val SUGGESTIONS_MAX = 8
    const val AUTOCOMPLETE_MAX = 5
    const val REMAINING_MAX = 3
    val SHOPPER_WINDOW = 15.minutes

    /** A phone whose clock is slightly ahead still counts as shopping now. */
    private val SHOPPER_AHEAD = 1.minutes
    private const val AUTOCOMPLETE_MIN_QUERY = 2

    /** All lists in creation order (ids are UUIDv7). */
    fun lists(inputs: ShoppingInputs): List<ShoppingListView> {
        val byList = itemsByList(inputs)
        return sortedLists(inputs).map { (id, data) -> listView(inputs, id, data, byList[id].orEmpty(), byList[id].orEmpty()) }
    }

    /**
     * The list page; [tagId] filters the items to buy and the bought ones (counts, progress and the "≥ 2 departments"
     * rule follow the filter; suggestions and tags in use do not). Null if the list does not exist.
     */
    fun detail(
        inputs: ShoppingInputs,
        listId: String,
        tagId: String? = null,
    ): ShoppingListDetail? {
        val data = inputs.lists.firstOrNull { it.first == listId }?.second ?: return null
        val all = itemsByList(inputs)[listId].orEmpty()
        val shown = if (tagId == null) all else all.filter { tagId in it.second.tagIds }
        val bought =
            shown
                .filter { it.second.status == ShoppingItemStatus.BOUGHT }
                .sortedWith(compareByDescending<Pair<String, ShoppingItemData>> { it.second.boughtAt }.thenBy { it.first })
                .map { itemView(inputs, it) }
        val suggestions =
            all
                .filter { it.second.status == ShoppingItemStatus.CLEARED }
                .sortedWith(
                    compareByDescending<Pair<String, ShoppingItemData>> { it.second.boughtCount }
                        .thenByDescending { it.second.boughtAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
                        .thenBy { it.second.name.lowercase() }
                        .thenBy { it.first },
                ).take(SUGGESTIONS_MAX)
                .map { itemView(inputs, it) }
        val inUse =
            all
                .filter { it.second.status != ShoppingItemStatus.CLEARED }
                .flatMapTo(mutableSetOf()) { it.second.tagIds }
        val viewer = inputs.viewer
        return ShoppingListDetail(
            list = listView(inputs, listId, data, all, shown),
            sections = sections(inputs, data, shown),
            bought = bought,
            suggestions = suggestions,
            tagsInUse = inputs.tags.filter { it.id in inUse },
            canEdit = viewer != null && ShoppingRules.canEditItems(viewer),
            canManage = viewer != null && ShoppingRules.canManageLists(viewer),
        )
    }

    /**
     * Names for the add field: this list's bought and cleared rows, then other lists' rows whose name this list does not
     * have. A candidate matches if its name key or one of its words starts with the query key.
     */
    fun autocomplete(
        inputs: ShoppingInputs,
        listId: String,
        query: String,
    ): List<Suggestion> {
        val q = ShoppingNames.key(query)
        if (q.length < AUTOCOMPLETE_MIN_QUERY) return emptyList()
        val all = itemsByList(inputs)
        val own = all[listId].orEmpty()
        val ownKeys = own.mapTo(mutableSetOf()) { key(it.second) }
        val candidates =
            own.filter { it.second.status != ShoppingItemStatus.NEEDED }.map { Candidate(it.first, it.second, true) } +
                all
                    .filterKeys { it != listId }
                    .values
                    .flatten()
                    .filter { key(it.second) !in ownKeys }
                    .map { Candidate(it.first, it.second, false) }
        return candidates
            .mapNotNull { c ->
                val k = key(c.data)
                when {
                    k.startsWith(q) -> c to 0
                    k.split(' ').any { it.startsWith(q) } -> c to 1
                    else -> null
                }
            }.sortedWith(
                compareByDescending<Pair<Candidate, Int>> { it.first.own }
                    .thenBy { it.second }
                    .thenByDescending { it.first.data.boughtCount }
                    .thenBy {
                        it.first.data.name
                            .lowercase()
                    }.thenBy { it.first.id },
            ).map { it.first }
            .distinctBy { key(it.data) }
            .take(AUTOCOMPLETE_MAX)
            .map { c ->
                if (c.own) {
                    Suggestion(c.data.name, c.data.category, c.data.note, c.id)
                } else {
                    Suggestion(c.data.name, c.data.category, null, null)
                }
            }
    }

    /** The list with something to buy and the latest activity (ties: the newer list), for the Today card. */
    fun active(inputs: ShoppingInputs): ActiveList? {
        val toBuy = lists(inputs).filter { it.needed > 0 }
        val latest =
            toBuy.maxWithOrNull(
                compareBy<ShoppingListView> { it.lastActivity?.toEpochMilliseconds() ?: Long.MIN_VALUE }.thenBy { it.id },
            ) ?: return null
        return ActiveList(latest, toBuy.size - 1)
    }

    /**
     * Who is shopping in a list (SHP-012): the member of the latest tick by someone else than [me] within the last
     * [SHOPPER_WINDOW] (and at most a minute ahead of [now]); null if none.
     */
    fun shopper(
        items: List<ShoppingItemData>,
        me: String?,
        now: Instant,
    ): String? =
        items
            .filter { item ->
                val at = item.boughtAt
                item.status == ShoppingItemStatus.BOUGHT &&
                    item.boughtBy != null &&
                    item.boughtBy != me &&
                    at != null &&
                    at >= now - SHOPPER_WINDOW &&
                    at <= now + SHOPPER_AHEAD
            }.maxByOrNull { it.boughtAt!! }
            ?.boughtBy

    /** The tag page section: items to buy with the tag in all lists (list order, then display order) and tagged lists. */
    fun forTag(
        inputs: ShoppingInputs,
        tagId: String,
    ): ShoppingTagView {
        val byList = itemsByList(inputs)
        val lists = sortedLists(inputs)
        val items =
            lists.flatMap { (id, data) ->
                val tagged = byList[id].orEmpty().filter { tagId in it.second.tagIds }
                displayOrder(inputs, data, tagged).map { it to data.name }
            }
        val tagged = lists.filter { tagId in it.second.tagIds }
        return ShoppingTagView(items, tagged.map { (id, data) -> listView(inputs, id, data, byList[id].orEmpty(), byList[id].orEmpty()) })
    }

    private data class Candidate(
        val id: String,
        val data: ShoppingItemData,
        val own: Boolean,
    )

    private fun key(data: ShoppingItemData) = ShoppingNames.key(data.name)

    private fun sortedLists(inputs: ShoppingInputs) = inputs.lists.sortedBy { it.first }

    /** Items grouped by list id; items of lists that do not exist are dropped. */
    private fun itemsByList(inputs: ShoppingInputs): Map<String, List<Pair<String, ShoppingItemData>>> {
        val listIds = inputs.lists.mapTo(mutableSetOf()) { it.first }
        return inputs.items.filter { it.second.listId in listIds }.groupBy { it.second.listId }
    }

    /**
     * [all] are the list's items (shopper, last activity), [shown] those that pass the page's tag filter (counts and
     * names to buy).
     */
    private fun listView(
        inputs: ShoppingInputs,
        id: String,
        data: ShoppingListData,
        all: List<Pair<String, ShoppingItemData>>,
        shown: List<Pair<String, ShoppingItemData>>,
    ): ShoppingListView {
        val shopperId = shopper(all.map { it.second }, inputs.viewer?.memberId, inputs.now)
        val lastActivity = all.flatMap { listOfNotNull(it.second.addedAt, it.second.boughtAt) }.maxOrNull()
        return ShoppingListView(
            id = id,
            data = data,
            needed = shown.count { it.second.status == ShoppingItemStatus.NEEDED },
            bought = shown.count { it.second.status == ShoppingItemStatus.BOUGHT },
            remaining = displayOrder(inputs, data, shown).take(REMAINING_MAX).map { it.data.name },
            shopper = shopperId?.let { member -> inputs.members.firstOrNull { it.id == member } },
            lastActivity = lastActivity,
            tags = inputs.tags.filter { it.id in data.tagIds },
        )
    }

    /** Items to buy in sections: by department in enum order when grouped and they span ≥ 2 departments. */
    private fun sections(
        inputs: ShoppingInputs,
        list: ShoppingListData,
        items: List<Pair<String, ShoppingItemData>>,
    ): List<DepartmentSection> {
        val needed =
            items
                .filter { it.second.status == ShoppingItemStatus.NEEDED }
                .sortedWith(compareBy<Pair<String, ShoppingItemData>> { it.second.addedAt }.thenBy { it.first })
                .map { itemView(inputs, it) }
        if (needed.isEmpty()) return emptyList()
        val byCategory = needed.groupBy { it.data.category }
        if (!list.groupByCategory || byCategory.size < 2) return listOf(DepartmentSection(null, needed))
        return byCategory.entries.sortedBy { it.key.ordinal }.map { DepartmentSection(it.key, it.value) }
    }

    private fun displayOrder(
        inputs: ShoppingInputs,
        list: ShoppingListData,
        items: List<Pair<String, ShoppingItemData>>,
    ): List<ShoppingItemView> = sections(inputs, list, items).flatMap { it.items }

    private fun itemView(
        inputs: ShoppingInputs,
        item: Pair<String, ShoppingItemData>,
    ): ShoppingItemView {
        val (id, data) = item
        return ShoppingItemView(
            id = id,
            data = data,
            tags = inputs.tags.filter { it.id in data.tagIds },
            addedBy = inputs.members.firstOrNull { it.id == data.addedBy },
            boughtBy = data.boughtBy?.let { member -> inputs.members.firstOrNull { it.id == member } },
            mine = inputs.viewer != null && data.addedBy == inputs.viewer.memberId,
        )
    }
}
