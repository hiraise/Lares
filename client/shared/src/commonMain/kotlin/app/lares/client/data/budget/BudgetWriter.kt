package app.lares.client.data.budget

import app.lares.client.auth.LocalSession
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.LocalWriter
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.budget.BudgetMath
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.sync.EntityTypes
import app.lares.contract.validation.Validators
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every user action on the budget (architecture/budget.md § Writer), through [LocalWriter]: rights and validation are
 * checked locally and the change works offline. Plan, month, planned-income receipt and funding rows have derived ids,
 * so two phones writing the same row offline merge (ADR-0032); every write carries the member as `updatedBy` /
 * `fundedBy`. Throws [LocalWriteException] when the change is not allowed or invalid.
 */
class BudgetWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
    private val clock: Clock,
) {
    /**
     * Saves [table] as the version in force from [from] (BUD-004). From the current month it also returns that month to
     * the plan (its month copy → null), keeping entered amounts and ticks (ASSUMPTION). `LocalWriter` writes one row per
     * call, so the version is written first: if the month write fails, the month only keeps its old copy.
     */
    suspend fun savePlan(
        table: BudgetTable,
        from: YearMonth,
    ) {
        val (me, current, monthRow) = localDb.read { Triple(me(), currentMonth(), monthRow(from)) }
        val data = BudgetPlanData(from, table, me.memberId, now())
        writer.upsert(EntityTypes.BUDGET_PLAN, Ids.budgetPlanRowId(me.householdId, from), encode(BudgetPlanData.serializer(), data))
        if (from == current && monthRow?.table != null) resetMonth(from)
    }

    /** Month-only changes (BUD-013); a table equal to the plan's is written as null (the month follows its plan). */
    suspend fun editMonth(
        month: YearMonth,
        table: BudgetTable,
    ) {
        val (me, plans, monthRow) = localDb.read { Triple(me(), plans(), monthRow(month)) }
        val plan = BudgetMath.planFor(month, plans)?.table
        val normalized = Validators.normalizeBudgetTable(table)
        val own = normalized.takeIf { plan == null || it != Validators.normalizeBudgetTable(plan) }
        if (own == null && monthRow?.table == null) return
        writeMonth(me, month, own)
    }

    /** «Вернуть как в плане»: the month follows its plan version again. */
    suspend fun resetMonth(month: YearMonth) {
        val (me, monthRow) = localDb.read { me() to monthRow(month) }
        if (monthRow?.table == null) return
        writeMonth(me, month, null)
    }

    /** Records the actual [amount] and [date] of planned income [incomeId] in [month] (BUD-011). */
    suspend fun recordIncome(
        month: YearMonth,
        incomeId: String,
        amount: Long,
        date: LocalDate,
    ) {
        val me = localDb.read { me() }
        val data = BudgetReceiptData(month, incomeId, amount = amount, date = date, updatedBy = me.memberId)
        writer.upsert(EntityTypes.BUDGET_RECEIPT, Ids.budgetReceiptRowId(incomeId, month), receiptFields(data))
    }

    /** Clears the actual amount and date of a planned income; its ticks stay. */
    suspend fun clearIncome(
        month: YearMonth,
        incomeId: String,
    ) {
        val me = localDb.read { me() }
        val id = Ids.budgetReceiptRowId(incomeId, month)
        if (localDb.read { budget_receiptQueries.selectById(id).executeAsOneOrNull() } == null) return
        writer.upsert(EntityTypes.BUDGET_RECEIPT, id, receiptFields(BudgetReceiptData(month, incomeId, updatedBy = me.memberId)))
    }

    /** Adds an extra income (a bonus, BUD-011 ASSUMPTION) and returns its receipt id. */
    suspend fun addExtraIncome(
        month: YearMonth,
        label: String?,
        amount: Long,
        date: LocalDate,
    ): String {
        val me = localDb.read { me() }
        val id = Ids.newId()
        writer.upsert(EntityTypes.BUDGET_RECEIPT, id, receiptFields(BudgetReceiptData(month, null, label, amount, date, me.memberId)))
        return id
    }

    suspend fun updateExtraIncome(
        id: String,
        label: String?,
        amount: Long,
        date: LocalDate,
    ) {
        val (me, current) = localDb.read { me() to extraIncome(id) }
        writer.upsert(
            EntityTypes.BUDGET_RECEIPT,
            id,
            receiptFields(current.copy(label = label, amount = amount, date = date, updatedBy = me.memberId)),
        )
    }

    suspend fun deleteExtraIncome(id: String) {
        localDb.read { extraIncome(id) }
        writer.delete(EntityTypes.BUDGET_RECEIPT, id)
    }

    /** Ticks [categoryId] in [incomeId]'s column of [month] with the transferred [amount] (BUD-012). */
    suspend fun fund(
        month: YearMonth,
        incomeId: String,
        categoryId: String,
        amount: Long,
    ) {
        val me = localDb.read { me() }
        val data = BudgetFundingData(month, incomeId, categoryId, amount, me.memberId, now())
        writer.upsert(
            EntityTypes.BUDGET_FUNDING,
            Ids.budgetFundingRowId(incomeId, categoryId, month),
            encode(BudgetFundingData.serializer(), data),
        )
    }

    /** Unticks: the amount becomes null, who ticked it last is kept. */
    suspend fun unfund(
        month: YearMonth,
        incomeId: String,
        categoryId: String,
    ) {
        val id = Ids.budgetFundingRowId(incomeId, categoryId, month)
        val current = localDb.read { budget_fundingQueries.selectById(id).executeAsOneOrNull() }
        if (current?.amount == null) return
        writer.upsert(EntityTypes.BUDGET_FUNDING, id, JsonObject(mapOf("amount" to JsonNull)))
    }

    /** «Отметить все»: ticks every unticked used category with a cell for [incomeId] with its planned amount, in table order. */
    suspend fun fundAll(
        month: YearMonth,
        incomeId: String,
    ) {
        val (table, ticked) =
            localDb.read {
                val table = BudgetMath.tableFor(month, plans(), monthRow(month))
                val ticked =
                    budget_fundingQueries
                        .selectAll()
                        .executeAsList()
                        .filter { it.month == month.toString() && it.income_id == incomeId && it.amount != null }
                        .mapTo(HashSet()) { it.category_id }
                table to ticked
            }
        if (table == null) return
        val cells = table.cells.filter { it.incomeId == incomeId }.associateBy { it.categoryId }
        for (category in table.categories) {
            val cell = cells[category.id] ?: continue
            if (category.unused || category.id in ticked) continue
            fund(month, incomeId, category.id, cell.amount)
        }
    }

    private suspend fun writeMonth(
        me: Me,
        month: YearMonth,
        table: BudgetTable?,
    ) {
        writer.upsert(
            EntityTypes.BUDGET_MONTH,
            Ids.budgetMonthRowId(me.householdId, month),
            encode(BudgetMonthData.serializer(), BudgetMonthData(month, table, me.memberId)),
        )
    }

    private fun receiptFields(data: BudgetReceiptData): JsonObject = encode(BudgetReceiptData.serializer(), data)

    /** Milliseconds, as the typed tables store them, so the local row and the shadow agree. */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    private fun LaresDb.me(): Me = LocalSession.me(this) ?: throw LocalForbiddenException("Not logged in")

    private fun LaresDb.currentMonth(): YearMonth {
        val zone =
            householdZone(
                householdQueries
                    .selectAll()
                    .executeAsList()
                    .firstOrNull()
                    ?.time_zone,
            )
        return clock
            .now()
            .toLocalDateTime(zone)
            .date.yearMonth
    }

    private fun LaresDb.plans(): List<BudgetPlanData> = budget_planQueries.selectAll().executeAsList().map { it.toData() }

    private fun LaresDb.monthRow(month: YearMonth): BudgetMonthData? =
        LocalSession.me(this)?.let { me ->
            budget_monthQueries.selectById(Ids.budgetMonthRowId(me.householdId, month)).executeAsOneOrNull()?.toData()
        }

    private fun LaresDb.extraIncome(id: String): BudgetReceiptData =
        budget_receiptQueries
            .selectById(id)
            .executeAsOneOrNull()
            ?.toData()
            ?.takeIf { it.incomeId == null }
            ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Extra income not found")

    /** Every field of [data], nulls included, so a cleared value (a month's table, an amount) travels as JSON null. */
    private fun <T> encode(
        serializer: KSerializer<T>,
        data: T,
    ): JsonObject {
        val json = LaresJson.encodeToJsonElement(serializer, data).jsonObject
        return JsonObject(
            (0 until serializer.descriptor.elementsCount).map(serializer.descriptor::getElementName).associateWith {
                json[it]
                    ?: JsonNull
            },
        )
    }
}
