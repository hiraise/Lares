package app.lares.server.shopping

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.ShoppingRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.sync.recordChange
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireAddedTags
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Shopping lists (SHP-001, SHP-006, TAG-002). Every member with `shopping.view` sees all lists (Q-018); creating,
 * renaming, tagging and deleting need `shopping.manage_lists` (`ShoppingRules`). Deleting a list tombstones its items.
 */
class ShoppingListSyncHandler : WritableSyncHandler<ShoppingListData>(ShoppingListData.serializer()) {
    override val type: String = EntityTypes.SHOPPING_LIST

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        ShoppingListTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[ShoppingListTable.deleted]
            ChangeRow(
                type,
                row[ShoppingListTable.id].toString(),
                row[ShoppingListTable.seq],
                deleted,
                if (deleted) null else toJson(ShoppingRepository.toListData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.SHOPPING_VIEW)

    override fun load(id: UUID): StoredEntity<ShoppingListData>? = ShoppingRepository.loadList(id)

    override fun normalize(data: ShoppingListData): ShoppingListData = Validators.normalizeShoppingList(data)

    override fun validate(
        ctx: MutationContext,
        data: ShoppingListData,
    ): Map<String, FieldErrorCode> = Validators.shoppingList(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: ShoppingListData?,
        merged: ShoppingListData?,
    ) {
        val allowed =
            when {
                existing != null -> ShoppingRules.canManageLists(ctx.viewer)
                merged != null -> ShoppingRules.canCreateList(ctx.viewer, merged)
                else -> false
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this shopping list")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: ShoppingListData?,
        data: ShoppingListData,
    ) {
        if (existing != null && existing.createdBy != data.createdBy) throw invalidField("createdBy")
        requireAddedTags(ctx, data.tagIds - existing?.tagIds.orEmpty())
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: ShoppingListData,
        seq: Long,
    ) {
        ShoppingListTable.insert {
            it[ShoppingListTable.id] = id
            it[householdId] = ctx.householdId
            ShoppingRepository.writeList(it, data)
            it[ShoppingListTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: ShoppingListData,
        seq: Long,
    ) {
        ShoppingListTable.update({ (ShoppingListTable.householdId eq ctx.householdId) and (ShoppingListTable.id eq id) }) {
            ShoppingRepository.writeList(it, data)
            it[ShoppingListTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Tombstones the list and then each live item, every item with its own seq (rows must not share a seq). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        ShoppingListTable.update({ (ShoppingListTable.householdId eq ctx.householdId) and (ShoppingListTable.id eq id) }) {
            it[deleted] = true
            it[ShoppingListTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        val tx = TransactionManager.current()
        val items =
            ShoppingItemTable
                .select(ShoppingItemTable.id)
                .where { (ShoppingItemTable.listId eq id) and (ShoppingItemTable.deleted eq false) }
                .orderBy(ShoppingItemTable.id)
                .map { it[ShoppingItemTable.id] }
        for (itemId in items) {
            val itemSeq = tx.recordChange(ctx.householdId)
            ShoppingItemTable.update({ ShoppingItemTable.id eq itemId }) {
                it[deleted] = true
                it[ShoppingItemTable.seq] = itemSeq
                it[updatedAt] = ctx.now
            }
        }
    }
}
