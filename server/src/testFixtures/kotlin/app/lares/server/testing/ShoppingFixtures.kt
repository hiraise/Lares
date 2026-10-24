package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

// Helpers for shopping tests (M2-02); reused by integration-tests.

/** A new shopping list by [createdBy] (a member id). [extra] adds or overrides fields, e.g. `mapOf("groupByCategory" to false)`. */
fun AppDeps.newShoppingList(
    createdBy: String,
    name: String = "Groceries",
    extra: Map<String, Any?> = emptyMap(),
    id: String = Ids.newId(),
): Mutation {
    val base: Map<String, JsonElement> = fields("name" to name, "createdBy" to createdBy)
    return upsert(EntityTypes.SHOPPING_LIST, id, JsonObject(base + fields(*extra.toList().toTypedArray())))
}

/** A new item "to buy" in [listId] added by [addedBy] (a member id) at [at]. [extra] adds or overrides fields. */
fun AppDeps.newShoppingItem(
    listId: String,
    addedBy: String,
    name: String = "Milk",
    at: Instant = clock.now(),
    extra: Map<String, Any?> = emptyMap(),
    id: String = Ids.newId(),
): Mutation {
    val base: Map<String, JsonElement> = fields("listId" to listId, "name" to name, "addedBy" to addedBy, "addedAt" to at.toString())
    return upsert(EntityTypes.SHOPPING_ITEM, id, JsonObject(base + fields(*extra.toList().toTypedArray())))
}
