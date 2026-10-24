package app.lares.server.shopping

import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.ShoppingListData
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.MutationContext
import java.util.UUID

// Server-only checks of the shopping handlers that need the database (architecture/shopping.md § Validation).

/** The parent list of an item: `ENTITY_NOT_FOUND` if missing or elsewhere, `ENTITY_DELETED` if tombstoned. */
internal fun requireParentList(
    ctx: MutationContext,
    listId: String,
): ShoppingListData {
    val id =
        runCatching { UUID.fromString(listId) }.getOrNull()
            ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Shopping list not found")
    val list = ShoppingRepository.getList(ctx.householdId, id) ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Shopping list not found")
    if (list.deleted) throw ApiException(ErrorCode.ENTITY_DELETED, "Shopping list was deleted")
    return list.data
}
