package app.lares.client.data.shopping

import app.lares.client.auth.LocalSession
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.toData
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.Viewer
import app.lares.contract.shopping.QuickAdd
import app.lares.contract.shopping.ShoppingCategories
import app.lares.contract.shopping.ShoppingNames
import app.lares.contract.sync.EntityTypes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every user action on shopping lists (architecture/shopping.md § Writer), through [LocalWriter]: rights and validation
 * are checked locally and the change works offline. Each write is one mutation of one entity. Throws
 * [LocalWriteException] when the change is not allowed or invalid.
 */
class ShoppingWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
    private val clock: Clock,
) {
    /** Creates a list with the member as its creator and returns its id. */
    suspend fun createList(name: String): String {
        val me = localDb.read { viewer() }
        val id = Ids.newId()
        val data = ShoppingListData(name, createdBy = me.memberId)
        writer.upsert(EntityTypes.SHOPPING_LIST, id, LaresJson.encodeToJsonElement(ShoppingListData.serializer(), data).jsonObject)
        return id
    }

    /** Changes only the given fields. */
    suspend fun updateList(
        id: String,
        name: String? = null,
        groupByCategory: Boolean? = null,
        tagIds: Set<String>? = null,
    ) {
        val fields =
            buildMap {
                name?.let { put("name", JsonPrimitive(it)) }
                groupByCategory?.let { put("groupByCategory", JsonPrimitive(it)) }
                tagIds?.let { put("tagIds", ids(it)) }
            }
        if (fields.isNotEmpty()) writer.upsert(EntityTypes.SHOPPING_LIST, id, JsonObject(fields))
    }

    /** Deletes the list; the server's cascade removes its items. */
    suspend fun deleteList(id: String) = writer.delete(EntityTypes.SHOPPING_LIST, id)

    /**
     * Quick add (SHP-002, SHP-009): parses [text], merges entries with the same name (the last quantity given wins) and
     * adds each one. With [tagId] (the page is filtered by that tag) the added items get the tag.
     */
    suspend fun add(
        listId: String,
        text: String,
        tagId: String? = null,
    ): AddResult {
        val merged = LinkedHashMap<String, Pair<String, String?>>()
        for (entry in QuickAdd.parse(text)) {
            val key = ShoppingNames.key(entry.name)
            val previous = merged[key]
            merged[key] = (previous?.first ?: entry.name) to (entry.quantity ?: previous?.second)
        }
        var added = 0
        val alreadyThere = mutableListOf<String>()
        for ((name, quantity) in merged.values) {
            val already = addOne(listId, name, quantity, null, tagId)
            if (already == null) added++ else alreadyThere += already
        }
        return AddResult(added, alreadyThere)
    }

    /** Adds a suggestion or an autocomplete entry: brings back the row it names, or adds the name as a new row. */
    suspend fun addSuggestion(
        listId: String,
        s: Suggestion,
        tagId: String? = null,
    ) {
        val row = s.itemId?.let { id -> localDb.read { item(id) } }
        if (row == null) {
            addOne(listId, s.name, null, s.category, tagId)
        } else if (row.second.status != ShoppingItemStatus.NEEDED) {
            bringBack(row.first, row.second, null, tagId)
        }
    }

    /** Ticks off (SHP-004) or unticks an item; nothing happens if it is already in that state. */
    suspend fun setBought(
        itemId: String,
        bought: Boolean,
    ) {
        val (me, data) = localDb.read { viewer() to requireItem(itemId) }
        val fields =
            when {
                bought && data.status != ShoppingItemStatus.BOUGHT -> {
                    mapOf(
                        "status" to status(ShoppingItemStatus.BOUGHT),
                        "boughtBy" to JsonPrimitive(me.memberId),
                        "boughtAt" to JsonPrimitive(now().toString()),
                        "boughtCount" to JsonPrimitive(data.boughtCount + 1),
                    )
                }

                !bought && data.status == ShoppingItemStatus.BOUGHT -> {
                    mapOf(
                        "status" to status(ShoppingItemStatus.NEEDED),
                        "boughtCount" to JsonPrimitive(maxOf(0, data.boughtCount - 1)),
                    )
                }

                else -> {
                    return
                }
            }
        writer.upsert(EntityTypes.SHOPPING_ITEM, itemId, JsonObject(fields))
    }

    /** "Clear bought": every bought item (only those with [tagId] when set) becomes cleared; returns their ids for undo. */
    suspend fun clearBought(
        listId: String,
        tagId: String? = null,
    ): List<String> {
        val ids =
            localDb.read {
                shopping_itemQueries
                    .selectByList(listId)
                    .executeAsList()
                    .map { it.id to it.toData() }
                    .filter { (_, data) -> data.status == ShoppingItemStatus.BOUGHT && (tagId == null || tagId in data.tagIds) }
                    .map { it.first }
            }
        ids.forEach { setStatus(it, ShoppingItemStatus.CLEARED) }
        return ids
    }

    /** Undo of clear (`BOUGHT`) or remove (`NEEDED`): sets only the status; items gone meanwhile are skipped. */
    suspend fun restore(
        ids: List<String>,
        status: ShoppingItemStatus,
    ) {
        val existing = localDb.read { ids.filter { shopping_itemQueries.selectById(it).executeAsOneOrNull() != null } }
        existing.forEach { setStatus(it, status) }
    }

    /** "Remove from list": the item becomes cleared (stays as a suggestion); returns the previous status for undo. */
    suspend fun remove(itemId: String): ShoppingItemStatus {
        val previous = localDb.read { requireItem(itemId).status }
        setStatus(itemId, ShoppingItemStatus.CLEARED)
        return previous
    }

    /** "Больше не предлагать": deletes the row. */
    suspend fun forget(itemId: String) = writer.delete(EntityTypes.SHOPPING_ITEM, itemId)

    /** Saves the item sheet; only fields that differ from the local copy are sent. */
    suspend fun updateItem(
        itemId: String,
        name: String,
        quantity: String?,
        note: String?,
        tagIds: Set<String>,
    ) {
        val current = localDb.read { requireItem(itemId) }
        val fields =
            buildMap<String, JsonElement> {
                if (name != current.name) put("name", JsonPrimitive(name))
                if (quantity != current.quantity) put("quantity", JsonPrimitive(quantity))
                if (note != current.note) put("note", JsonPrimitive(note))
                if (tagIds != current.tagIds) put("tagIds", ids(tagIds))
            }
        if (fields.isNotEmpty()) writer.upsert(EntityTypes.SHOPPING_ITEM, itemId, JsonObject(fields))
    }

    /**
     * Changes the department of the item, then (best effort, Q-019) of every other local item with the same name in a
     * list that exists locally; a refused extra row is skipped and never fails the user's own change.
     */
    suspend fun setCategory(
        itemId: String,
        category: ProductCategory,
    ) {
        val others =
            localDb.read {
                val item = requireItem(itemId)
                shopping_itemQueries
                    .selectByKey(ShoppingNames.key(item.name))
                    .executeAsList()
                    .filter { row ->
                        row.id != itemId &&
                            row.category != category.key &&
                            shopping_listQueries.selectById(row.list_id).executeAsOneOrNull() != null
                    }.map { it.id }
            }
        val fields = JsonObject(mapOf("category" to JsonPrimitive(category.key)))
        writer.upsert(EntityTypes.SHOPPING_ITEM, itemId, fields)
        for (id in others) {
            try {
                writer.upsert(EntityTypes.SHOPPING_ITEM, id, fields)
            } catch (_: LocalWriteException) {
                // Another member's row we may not change, or one being deleted: the correction stays partial.
            }
        }
    }

    /**
     * Adds one name (ADR-0021): an item of the list with the same name key is kept when it is to buy (returns its name
     * for "already in the list") or brought back otherwise; a new name creates a row in [category], else in the
     * department the household already uses for it, else the dictionary's guess. Returns null when an item was added.
     */
    private suspend fun addOne(
        listId: String,
        name: String,
        quantity: String?,
        category: ProductCategory?,
        tagId: String?,
    ): String? {
        val key = ShoppingNames.key(name)
        val (me, existing, newCategory) =
            localDb.read {
                val rows = shopping_itemQueries.selectByListAndKey(listId, key).executeAsList().map { it.id to it.toData() }
                val existing =
                    rows.maxWithOrNull(
                        compareBy<Pair<String, ShoppingItemData>> { it.second.status == ShoppingItemStatus.NEEDED }
                            .thenBy { it.second.addedAt },
                    )
                Triple(viewer(), existing, if (existing == null) category ?: categoryFor(key, name) else null)
            }
        if (existing == null) {
            val data =
                ShoppingItemData(
                    listId = listId,
                    name = name,
                    addedBy = me.memberId,
                    addedAt = now(),
                    category = newCategory ?: ProductCategory.OTHER,
                    quantity = quantity,
                    tagIds = setOfNotNull(tagId),
                )
            writer.upsert(
                EntityTypes.SHOPPING_ITEM,
                Ids.newId(),
                LaresJson.encodeToJsonElement(ShoppingItemData.serializer(), data).jsonObject,
            )
            return null
        }
        val (id, data) = existing
        if (data.status != ShoppingItemStatus.NEEDED) {
            bringBack(id, data, quantity, tagId)
            return null
        }
        val fields =
            buildMap {
                if (quantity != null && quantity != data.quantity) put("quantity", JsonPrimitive(quantity))
                if (tagId != null && tagId !in data.tagIds) put("tagIds", ids(data.tagIds + tagId))
            }
        if (fields.isNotEmpty()) writer.upsert(EntityTypes.SHOPPING_ITEM, id, JsonObject(fields))
        return data.name
    }

    /** A bought or cleared row becomes an item to buy again, added by the member now; its note and department stay. */
    private suspend fun bringBack(
        id: String,
        data: ShoppingItemData,
        quantity: String?,
        tagId: String?,
    ) {
        val me = localDb.read { viewer() }
        val fields =
            buildMap {
                put("status", status(ShoppingItemStatus.NEEDED))
                put("addedBy", JsonPrimitive(me.memberId))
                put("addedAt", JsonPrimitive(now().toString()))
                put("quantity", JsonPrimitive(quantity))
                if (tagId != null && tagId !in data.tagIds) put("tagIds", ids(data.tagIds + tagId))
            }
        writer.upsert(EntityTypes.SHOPPING_ITEM, id, JsonObject(fields))
    }

    private suspend fun setStatus(
        id: String,
        status: ShoppingItemStatus,
    ) = writer.upsert(EntityTypes.SHOPPING_ITEM, id, JsonObject(mapOf("status" to status(status))))

    /** The department of the household's latest row with this key in a list that exists locally, else the dictionary's. */
    private fun LaresDb.categoryFor(
        key: String,
        name: String,
    ): ProductCategory =
        shopping_itemQueries
            .selectByKey(key)
            .executeAsList()
            .filter { shopping_listQueries.selectById(it.list_id).executeAsOneOrNull() != null }
            .maxByOrNull { it.added_at }
            ?.let { ProductCategory.fromKey(it.category) }
            ?: ShoppingCategories.guess(name)

    private fun LaresDb.viewer(): Viewer = LocalSession.viewer(this) ?: throw LocalForbiddenException("Not logged in")

    private fun LaresDb.item(id: String): Pair<String, ShoppingItemData>? =
        shopping_itemQueries.selectById(id).executeAsOneOrNull()?.let { it.id to it.toData() }

    private fun LaresDb.requireItem(id: String): ShoppingItemData =
        item(id)?.second ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Shopping item not found")

    /** Whole milliseconds, as the typed table stores them, so the local row and the shadow agree. */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    private fun status(status: ShoppingItemStatus) = JsonPrimitive(status.key)

    private fun ids(ids: Set<String>): JsonElement = LaresJson.encodeToJsonElement(ListSerializer(String.serializer()), ids.sorted())
}
