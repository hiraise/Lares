package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.BudgetPlanData
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
 * One plan version in force from its month (BUD-004, ADR-0032). The id is derived from household and month, so two
 * phones saving the same version write one row. Versions of past months are read-only: every upsert of one is
 * rejected, also one that changes nothing. Clients never delete versions (`WriteRules`).
 */
class BudgetPlanSyncHandler : WritableSyncHandler<BudgetPlanData>(BudgetPlanData.serializer()) {
    override val type: String = EntityTypes.BUDGET_PLAN

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        BudgetPlanTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[BudgetPlanTable.deleted]
            ChangeRow(
                type,
                row[BudgetPlanTable.id].toString(),
                row[BudgetPlanTable.seq],
                deleted,
                if (deleted) null else toJson(BudgetRepository.toPlanData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.BUDGET_VIEW)

    override fun load(id: UUID): StoredEntity<BudgetPlanData>? = BudgetRepository.loadPlan(id)

    override fun normalize(data: BudgetPlanData): BudgetPlanData = Validators.normalizeBudgetPlan(data)

    /** The past-month check is here, not in [checkUpsert], so that an unchanged upsert of a past version is rejected too. */
    override fun validate(
        ctx: MutationContext,
        data: BudgetPlanData,
    ): Map<String, FieldErrorCode> {
        val errors = Validators.budgetPlan(data)
        if ("effectiveFrom" in errors || !isPastMonth(ctx, data.effectiveFrom)) return errors
        return errors + ("effectiveFrom" to FieldErrorCode.OUT_OF_RANGE)
    }

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: BudgetPlanData?,
        merged: BudgetPlanData?,
    ) = requireBudgetWriter(ctx, op, type, merged != null && BudgetRules.foreignUpdater(ctx.viewer, merged.updatedBy))

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: BudgetPlanData?,
        data: BudgetPlanData,
    ) {
        if (existing != null && existing.effectiveFrom != data.effectiveFrom) throw invalidField("effectiveFrom")
        requireDerivedId(id) { Ids.budgetPlanRowId(ctx.householdId.toString(), data.effectiveFrom) }
        requireNotFuture(ctx, "updatedAt", data.updatedAt, existing?.updatedAt)
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: BudgetPlanData,
        seq: Long,
    ) {
        BudgetPlanTable.insert {
            it[BudgetPlanTable.id] = id
            it[householdId] = ctx.householdId
            BudgetRepository.writePlan(it, data)
            it[BudgetPlanTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: BudgetPlanData,
        seq: Long,
    ) {
        BudgetPlanTable.update({ (BudgetPlanTable.householdId eq ctx.householdId) and (BudgetPlanTable.id eq id) }) {
            BudgetRepository.writePlan(it, data)
            it[BudgetPlanTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes). */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Plan versions cannot be deleted")
}
