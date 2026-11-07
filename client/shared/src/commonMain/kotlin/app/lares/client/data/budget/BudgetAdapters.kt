package app.lares.client.data.budget

import app.lares.client.auth.LocalSession
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LaresDb
import app.lares.client.sync.EntityAdapter
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.client.sync.forbidden
import app.lares.client.sync.invalid
import app.lares.client.sync.requireDerivedId
import app.lares.contract.Ids
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.lares.client.db.Budget_funding as FundingRow
import app.lares.client.db.Budget_month as MonthRow
import app.lares.client.db.Budget_plan as PlanRow
import app.lares.client.db.Budget_receipt as ReceiptRow

// Budget adapters (architecture/budget.md § Adapters): the same BudgetRules, derived-id, immutable-field and time checks
// as the server's handlers (M5-02), so a write the server would reject fails on the phone before sync.

internal fun decodeTable(json: String): BudgetTable = LaresJson.decodeFromString(BudgetTable.serializer(), json)

internal fun encodeTable(table: BudgetTable): String = LaresJson.encodeToString(BudgetTable.serializer(), table)

internal fun PlanRow.toData() =
    BudgetPlanData(YearMonth.parse(effective_from), decodeTable(plan_table), updated_by, Instant.fromEpochMilliseconds(updated_at))

internal fun MonthRow.toData() = BudgetMonthData(YearMonth.parse(month), month_table?.let(::decodeTable), updated_by)

internal fun ReceiptRow.toData() =
    BudgetReceiptData(YearMonth.parse(month), income_id, label, amount, date?.let(LocalDate::parse), updated_by)

internal fun FundingRow.toData() =
    BudgetFundingData(YearMonth.parse(month), income_id, category_id, amount, funded_by, funded_at?.let(Instant::fromEpochMilliseconds))

/** The logged-in member's household, the base of the plan and month row ids. */
private fun householdId(db: LaresDb): String? = LocalSession.me(db)?.householdId

/** `budget.manage` and, for rows that carry their writer, the writer's own id; else `FORBIDDEN`. */
private fun requireBudgetWriter(
    viewer: Viewer,
    op: MutationOp,
    foreign: Boolean,
) {
    if (!BudgetRules.canManage(viewer) || foreign) forbidden(op, "budget row")
}

/** A referenced id must be a lowercase UUID, as the server stores and returns it. */
@OptIn(ExperimentalUuidApi::class)
private fun requireUuid(
    field: String,
    value: String,
) {
    if (runCatching { Uuid.parse(value).toString() }.getOrNull() != value) invalid(field)
}

/** An extra income's random id must be a UUIDv7 (derived ids are version 8). */
@OptIn(ExperimentalUuidApi::class)
private fun requireUuidV7(id: String) {
    val uuid = runCatching { Uuid.parse(id) }.getOrNull()
    if (uuid == null || uuid.toByteArray()[VERSION_BYTE].toInt() ushr VERSION_SHIFT != UUID_V7) invalid("id")
}

/** A time changed to later than `now + 1 day` (a clock far ahead): `OUT_OF_RANGE`, as on the server. */
private fun requireNotFuture(
    clock: Clock,
    field: String,
    value: Instant?,
    stored: Instant?,
) {
    if (value != null && value != stored && value > clock.now() + 1.days) {
        throw LocalValidationException(mapOf(field to FieldErrorCode.OUT_OF_RANGE))
    }
}

class BudgetPlanAdapter(
    private val clock: Clock,
) : EntityAdapter<BudgetPlanData>(EntityTypes.BUDGET_PLAN, BudgetPlanData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.budget_planQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: BudgetPlanData,
    ) {
        db.budget_planQueries.upsert(
            id,
            data.effectiveFrom.toString(),
            encodeTable(data.table),
            data.updatedBy,
            data.updatedAt.toEpochMilliseconds(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.budget_planQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.budget_planQueries.deleteAll()
    }

    override fun normalize(data: BudgetPlanData) = Validators.normalizeBudgetPlan(data)

    override fun validate(data: BudgetPlanData) = Validators.budgetPlan(data)

    /**
     * Past versions are read-only (BUD-004): `effectiveFrom` before the month of `now − 1 day` in the household zone,
     * the server's rule with its day of grace.
     */
    override fun localConflicts(
        db: LaresDb,
        id: String,
        data: BudgetPlanData,
    ): Map<String, FieldErrorCode> {
        val zone =
            householdZone(
                db.householdQueries
                    .selectAll()
                    .executeAsList()
                    .firstOrNull()
                    ?.time_zone,
            )
        val earliest = (clock.now() - 1.days).toLocalDateTime(zone).date.yearMonth
        return if (data.effectiveFrom < earliest) mapOf("effectiveFrom" to FieldErrorCode.OUT_OF_RANGE) else emptyMap()
    }

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: BudgetPlanData?,
        merged: BudgetPlanData?,
    ) {
        requireBudgetWriter(viewer, op, merged != null && BudgetRules.foreignUpdater(viewer, merged.updatedBy))
        if (merged == null) return
        if (current != null && current.effectiveFrom != merged.effectiveFrom) invalid("effectiveFrom")
        requireDerivedId(id) { Ids.budgetPlanRowId(householdId(db)!!, merged.effectiveFrom) }
        requireNotFuture(clock, "updatedAt", merged.updatedAt, current?.updatedAt)
    }

    /** Never reached: `WriteRules` refuses deletes of plan versions. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: BudgetPlanData,
    ): Unit = throw LocalForbiddenException("Plan versions cannot be deleted")
}

class BudgetMonthAdapter : EntityAdapter<BudgetMonthData>(EntityTypes.BUDGET_MONTH, BudgetMonthData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.budget_monthQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: BudgetMonthData,
    ) {
        db.budget_monthQueries.upsert(id, data.month.toString(), data.table?.let(::encodeTable), data.updatedBy)
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.budget_monthQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.budget_monthQueries.deleteAll()
    }

    override fun normalize(data: BudgetMonthData) = Validators.normalizeBudgetMonth(data)

    override fun validate(data: BudgetMonthData) = Validators.budgetMonth(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: BudgetMonthData?,
        merged: BudgetMonthData?,
    ) {
        requireBudgetWriter(viewer, op, merged != null && BudgetRules.foreignUpdater(viewer, merged.updatedBy))
        if (merged == null) return
        if (current != null && current.month != merged.month) invalid("month")
        requireDerivedId(id) { Ids.budgetMonthRowId(householdId(db)!!, merged.month) }
    }

    /** Never reached: `WriteRules` refuses deletes of month rows. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: BudgetMonthData,
    ): Unit = throw LocalForbiddenException("Month rows cannot be deleted")
}

class BudgetReceiptAdapter :
    EntityAdapter<BudgetReceiptData>(EntityTypes.BUDGET_RECEIPT, BudgetReceiptData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.budget_receiptQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: BudgetReceiptData,
    ) {
        db.budget_receiptQueries.upsert(
            id,
            data.month.toString(),
            data.incomeId,
            data.label,
            data.amount,
            data.date?.toString(),
            data.updatedBy,
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.budget_receiptQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.budget_receiptQueries.deleteAll()
    }

    override fun normalize(data: BudgetReceiptData) = Validators.normalizeBudgetReceipt(data)

    override fun validate(data: BudgetReceiptData) = Validators.budgetReceipt(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: BudgetReceiptData?,
        merged: BudgetReceiptData?,
    ) {
        requireBudgetWriter(viewer, op, merged != null && BudgetRules.foreignUpdater(viewer, merged.updatedBy))
        if (merged == null) {
            if (current?.incomeId != null) throw LocalForbiddenException("Planned income receipts cannot be deleted")
            return
        }
        if (current != null) {
            if (current.month != merged.month) invalid("month")
            if (current.incomeId != merged.incomeId) invalid("incomeId")
        }
        val incomeId = merged.incomeId
        if (incomeId == null) {
            requireUuidV7(id)
        } else {
            requireUuid("incomeId", incomeId)
            requireDerivedId(id) { Ids.budgetReceiptRowId(incomeId, merged.month) }
        }
    }
}

class BudgetFundingAdapter(
    private val clock: Clock,
) : EntityAdapter<BudgetFundingData>(EntityTypes.BUDGET_FUNDING, BudgetFundingData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.budget_fundingQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: BudgetFundingData,
    ) {
        db.budget_fundingQueries.upsert(
            id,
            data.month.toString(),
            data.incomeId,
            data.categoryId,
            data.amount,
            data.fundedBy,
            data.fundedAt?.toEpochMilliseconds(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.budget_fundingQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.budget_fundingQueries.deleteAll()
    }

    override fun validate(data: BudgetFundingData) = Validators.budgetFunding(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: BudgetFundingData?,
        merged: BudgetFundingData?,
    ) {
        requireBudgetWriter(viewer, op, merged != null && BudgetRules.foreignFunder(viewer, current, merged))
        if (merged == null) return
        if (current != null) {
            if (current.month != merged.month) invalid("month")
            if (current.incomeId != merged.incomeId) invalid("incomeId")
            if (current.categoryId != merged.categoryId) invalid("categoryId")
        }
        requireUuid("incomeId", merged.incomeId)
        requireUuid("categoryId", merged.categoryId)
        requireDerivedId(id) { Ids.budgetFundingRowId(merged.incomeId, merged.categoryId, merged.month) }
        requireNotFuture(clock, "fundedAt", merged.fundedAt, current?.fundedAt)
    }

    /** Never reached: `WriteRules` refuses deletes of ticks. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: BudgetFundingData,
    ): Unit = throw LocalForbiddenException("Funding rows cannot be deleted")
}

private const val VERSION_BYTE = 6
private const val VERSION_SHIFT = 4
private const val UUID_V7 = 7
