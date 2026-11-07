package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.BudgetMonthData
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
 * Month-only changes of the plan (BUD-013). The id is derived from household and month; a null table returns the month
 * to its plan version. Clients never delete these rows (`WriteRules`).
 */
class BudgetMonthSyncHandler : WritableSyncHandler<BudgetMonthData>(BudgetMonthData.serializer()) {
    override val type: String = EntityTypes.BUDGET_MONTH

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        BudgetMonthTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[BudgetMonthTable.deleted]
            ChangeRow(
                type,
                row[BudgetMonthTable.id].toString(),
                row[BudgetMonthTable.seq],
                deleted,
                if (deleted) null else toJson(BudgetRepository.toMonthData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.BUDGET_VIEW)

    override fun load(id: UUID): StoredEntity<BudgetMonthData>? = BudgetRepository.loadMonth(id)

    override fun normalize(data: BudgetMonthData): BudgetMonthData = Validators.normalizeBudgetMonth(data)

    override fun validate(
        ctx: MutationContext,
        data: BudgetMonthData,
    ): Map<String, FieldErrorCode> = Validators.budgetMonth(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: BudgetMonthData?,
        merged: BudgetMonthData?,
    ) = requireBudgetWriter(ctx, op, type, merged != null && BudgetRules.foreignUpdater(ctx.viewer, merged.updatedBy))

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: BudgetMonthData?,
        data: BudgetMonthData,
    ) {
        if (existing != null && existing.month != data.month) throw invalidField("month")
        requireDerivedId(id) { Ids.budgetMonthRowId(ctx.householdId.toString(), data.month) }
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: BudgetMonthData,
        seq: Long,
    ) {
        BudgetMonthTable.insert {
            it[BudgetMonthTable.id] = id
            it[householdId] = ctx.householdId
            BudgetRepository.writeMonth(it, data)
            it[BudgetMonthTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: BudgetMonthData,
        seq: Long,
    ) {
        BudgetMonthTable.update({ (BudgetMonthTable.householdId eq ctx.householdId) and (BudgetMonthTable.id eq id) }) {
            BudgetRepository.writeMonth(it, data)
            it[BudgetMonthTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Month rows cannot be deleted")
}
