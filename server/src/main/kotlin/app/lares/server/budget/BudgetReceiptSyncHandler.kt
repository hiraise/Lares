package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireDerivedId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * An actual income of a month (BUD-011). A planned income's receipt has an id derived from income and month and is
 * never deleted; an extra income (no `incomeId`) has a random UUIDv7 and may be deleted.
 */
class BudgetReceiptSyncHandler : WritableSyncHandler<BudgetReceiptData>(BudgetReceiptData.serializer()) {
    override val type: String = EntityTypes.BUDGET_RECEIPT

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        BudgetReceiptTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[BudgetReceiptTable.deleted]
            ChangeRow(
                type,
                row[BudgetReceiptTable.id].toString(),
                row[BudgetReceiptTable.seq],
                deleted,
                if (deleted) null else toJson(BudgetRepository.toReceiptData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.BUDGET_VIEW)

    override fun load(id: UUID): StoredEntity<BudgetReceiptData>? = BudgetRepository.loadReceipt(id)

    override fun normalize(data: BudgetReceiptData): BudgetReceiptData = Validators.normalizeBudgetReceipt(data)

    override fun validate(
        ctx: MutationContext,
        data: BudgetReceiptData,
    ): Map<String, FieldErrorCode> = Validators.budgetReceipt(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: BudgetReceiptData?,
        merged: BudgetReceiptData?,
    ) {
        requireBudgetWriter(ctx, op, type, merged != null && BudgetRules.foreignUpdater(ctx.viewer, merged.updatedBy))
        if (merged == null && existing?.incomeId != null) {
            throw ApiException(ErrorCode.FORBIDDEN, "Planned income receipts cannot be deleted")
        }
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: BudgetReceiptData?,
        data: BudgetReceiptData,
    ) {
        if (existing != null) {
            if (existing.month != data.month) throw invalidField("month")
            if (existing.incomeId != data.incomeId) throw invalidField("incomeId")
        }
        val incomeId = data.incomeId
        if (incomeId == null) {
            requireUuidV7(id)
        } else {
            requireUuid("incomeId", incomeId)
            requireDerivedId(id) { Ids.budgetReceiptRowId(incomeId, data.month) }
        }
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: BudgetReceiptData,
        seq: Long,
    ) {
        BudgetReceiptTable.insert {
            it[BudgetReceiptTable.id] = id
            it[householdId] = ctx.householdId
            BudgetRepository.writeReceipt(it, data)
            it[BudgetReceiptTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: BudgetReceiptData,
        seq: Long,
    ) {
        BudgetReceiptTable.update({ (BudgetReceiptTable.householdId eq ctx.householdId) and (BudgetReceiptTable.id eq id) }) {
            BudgetRepository.writeReceipt(it, data)
            it[BudgetReceiptTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        BudgetReceiptTable.update({ (BudgetReceiptTable.householdId eq ctx.householdId) and (BudgetReceiptTable.id eq id) }) {
            it[deleted] = true
            it[BudgetReceiptTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }
}
