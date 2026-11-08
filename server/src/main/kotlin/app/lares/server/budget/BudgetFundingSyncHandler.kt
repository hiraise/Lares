package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.BudgetFundingData
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
 * One funding tick: a category's money from one income in one month (BUD-012). The id is derived from income, category
 * and month, so two members ticking the same category write one row (the later amount wins). Unticking sets the amount
 * to null and keeps `fundedBy`. The server does not check that the income and category are in the month's table (stale
 * ticks are ignored by `BudgetMath`). Clients never delete these rows (`WriteRules`).
 */
class BudgetFundingSyncHandler : WritableSyncHandler<BudgetFundingData>(BudgetFundingData.serializer()) {
    override val type: String = EntityTypes.BUDGET_FUNDING

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        BudgetFundingTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[BudgetFundingTable.deleted]
            ChangeRow(
                type,
                row[BudgetFundingTable.id].toString(),
                row[BudgetFundingTable.seq],
                deleted,
                if (deleted) null else toJson(BudgetRepository.toFundingData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.BUDGET_VIEW)

    override fun load(id: UUID): StoredEntity<BudgetFundingData>? = BudgetRepository.loadFunding(id)

    override fun validate(
        ctx: MutationContext,
        data: BudgetFundingData,
    ): Map<String, FieldErrorCode> = Validators.budgetFunding(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: BudgetFundingData?,
        merged: BudgetFundingData?,
    ) = requireBudgetWriter(ctx, op, type, merged != null && BudgetRules.foreignFunder(ctx.viewer, existing, merged))

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: BudgetFundingData?,
        data: BudgetFundingData,
    ) {
        if (existing != null) {
            if (existing.month != data.month) throw invalidField("month")
            if (existing.incomeId != data.incomeId) throw invalidField("incomeId")
            if (existing.categoryId != data.categoryId) throw invalidField("categoryId")
        }
        requireUuid("incomeId", data.incomeId)
        requireUuid("categoryId", data.categoryId)
        requireDerivedId(id) { Ids.budgetFundingRowId(data.incomeId, data.categoryId, data.month) }
        requireNotFuture(ctx, "fundedAt", data.fundedAt, existing?.fundedAt)
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: BudgetFundingData,
        seq: Long,
    ) {
        BudgetFundingTable.insert {
            it[BudgetFundingTable.id] = id
            it[householdId] = ctx.householdId
            BudgetRepository.writeFunding(it, data)
            it[BudgetFundingTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: BudgetFundingData,
        seq: Long,
    ) {
        BudgetFundingTable.update({ (BudgetFundingTable.householdId eq ctx.householdId) and (BudgetFundingTable.id eq id) }) {
            BudgetRepository.writeFunding(it, data)
            it[BudgetFundingTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Funding rows cannot be deleted")
}
