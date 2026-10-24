package app.lares.server.shopping

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.ShoppingRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.toContract
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
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Shopping items (SHP-002, SHP-004, SHP-005, SHP-014, ADR-0021): adding, ticking, clearing and changing need
 * `shopping.edit`; the author fields (`addedBy`, `boughtBy`) a write sets must be the writer. Kept after they are bought.
 */
class ShoppingItemSyncHandler : WritableSyncHandler<ShoppingItemData>(ShoppingItemData.serializer()) {
    override val type: String = EntityTypes.SHOPPING_ITEM

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        ShoppingItemTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[ShoppingItemTable.deleted]
            ChangeRow(
                type,
                row[ShoppingItemTable.id].toString(),
                row[ShoppingItemTable.seq],
                deleted,
                if (deleted) null else toJson(ShoppingRepository.toItemData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.SHOPPING_VIEW)

    override fun load(id: UUID): StoredEntity<ShoppingItemData>? = ShoppingRepository.loadItem(id)

    override fun normalize(data: ShoppingItemData): ShoppingItemData = Validators.normalizeShoppingItem(data)

    override fun validate(
        ctx: MutationContext,
        data: ShoppingItemData,
    ): Map<String, FieldErrorCode> = Validators.shoppingItem(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: ShoppingItemData?,
        merged: ShoppingItemData?,
    ) {
        val allowed =
            if (merged != null) {
                // The stored parent decides; a changed listId is rejected by checkUpsert.
                requireParentList(ctx, (existing ?: merged).listId)
                ShoppingRules.canEditItems(ctx.viewer) && ShoppingRules.foreignAuthorFields(ctx.viewer, existing, merged).isEmpty()
            } else {
                ShoppingRules.canEditItems(ctx.viewer)
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this shopping item")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: ShoppingItemData?,
        data: ShoppingItemData,
    ) {
        if (existing != null && existing.listId != data.listId) throw invalidField("listId")
        requireAddedTags(ctx, data.tagIds - existing?.tagIds.orEmpty())
        // A phone whose clock is more than a day ahead gets its adds and ticks rejected (as task completions).
        val latest = ctx.now.toContract() + 1.days
        if (isFutureChange(data.addedAt, existing?.addedAt, latest)) throw invalidField("addedAt", FieldErrorCode.OUT_OF_RANGE)
        if (isFutureChange(data.boughtAt, existing?.boughtAt, latest)) throw invalidField("boughtAt", FieldErrorCode.OUT_OF_RANGE)
    }

    private fun isFutureChange(
        value: Instant?,
        stored: Instant?,
        latest: Instant,
    ): Boolean = value != null && value != stored && value > latest

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: ShoppingItemData,
        seq: Long,
    ) {
        ShoppingItemTable.insert {
            it[ShoppingItemTable.id] = id
            it[householdId] = ctx.householdId
            ShoppingRepository.writeItem(it, data)
            it[ShoppingItemTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: ShoppingItemData,
        seq: Long,
    ) {
        ShoppingItemTable.update({ (ShoppingItemTable.householdId eq ctx.householdId) and (ShoppingItemTable.id eq id) }) {
            ShoppingRepository.writeItem(it, data)
            it[ShoppingItemTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        ShoppingItemTable.update({ (ShoppingItemTable.householdId eq ctx.householdId) and (ShoppingItemTable.id eq id) }) {
            it[deleted] = true
            it[ShoppingItemTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }
}
