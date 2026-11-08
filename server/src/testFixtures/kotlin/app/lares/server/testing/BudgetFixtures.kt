package app.lares.server.testing

import app.lares.contract.Ids
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetTable
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.time.Instant

// Helpers for budget tests (M5-02); reused by integration-tests. Ids are derived as the clients derive them.

/** A small valid table: two categories funded from two incomes (rows and columns add up). */
fun sampleBudgetTable(): BudgetTable {
    val food = BudgetCategory(Ids.newId(), "Food", 30_000)
    val rent = BudgetCategory(Ids.newId(), "Rent", 50_000)
    val salary = BudgetIncome(Ids.newId(), 10, 60_000)
    val advance = BudgetIncome(Ids.newId(), 25, 20_000)
    return BudgetTable(
        categories = listOf(food, rent),
        incomes = listOf(salary, advance),
        cells =
            listOf(
                BudgetCell(food.id, salary.id, 10_000),
                BudgetCell(food.id, advance.id, 20_000),
                BudgetCell(rent.id, salary.id, 50_000),
            ),
    )
}

fun budgetTableJson(table: BudgetTable) = LaresJson.encodeToJsonElement(BudgetTable.serializer(), table)

/** An upsert of the plan version from [effectiveFrom] by [updatedBy] with its derived id unless [id] is given. */
fun AppDeps.newBudgetPlan(
    householdId: String,
    effectiveFrom: YearMonth,
    updatedBy: String,
    table: BudgetTable = sampleBudgetTable(),
    updatedAt: Instant = clock.now(),
    id: String = Ids.budgetPlanRowId(householdId, effectiveFrom),
): Mutation =
    upsert(
        EntityTypes.BUDGET_PLAN,
        id,
        fields(
            "effectiveFrom" to effectiveFrom.toString(),
            "table" to budgetTableJson(table),
            "updatedBy" to updatedBy,
            "updatedAt" to updatedAt.toString(),
        ),
    )

/** An upsert of [month]'s own table ([table] null: the month follows its plan) with its derived id unless [id] is given. */
fun AppDeps.budgetMonth(
    householdId: String,
    month: YearMonth,
    updatedBy: String,
    table: BudgetTable? = sampleBudgetTable(),
    id: String = Ids.budgetMonthRowId(householdId, month),
): Mutation =
    upsert(
        EntityTypes.BUDGET_MONTH,
        id,
        fields("month" to month.toString(), "table" to table?.let(::budgetTableJson), "updatedBy" to updatedBy),
    )

/**
 * An upsert of the receipt of planned income [incomeId] in [month] (derived id), or of an extra income when [incomeId] is
 * null (a new random id). [extra] adds or overrides fields.
 */
fun AppDeps.budgetReceipt(
    month: YearMonth,
    incomeId: String?,
    updatedBy: String,
    amount: Long? = null,
    date: LocalDate? = null,
    extra: Map<String, Any?> = emptyMap(),
    id: String = incomeId?.let { Ids.budgetReceiptRowId(it, month) } ?: Ids.newId(),
): Mutation =
    upsert(
        EntityTypes.BUDGET_RECEIPT,
        id,
        fields(
            "month" to month.toString(),
            "incomeId" to incomeId,
            "amount" to amount,
            "date" to date?.toString(),
            "updatedBy" to updatedBy,
            *extra.toList().toTypedArray(),
        ),
    )

/** An upsert of the tick of [categoryId] from [incomeId] in [month] (derived id); a null [amount] unticks it. */
fun AppDeps.budgetFunding(
    month: YearMonth,
    incomeId: String,
    categoryId: String,
    amount: Long?,
    fundedBy: String,
    fundedAt: Instant = clock.now(),
    id: String = Ids.budgetFundingRowId(incomeId, categoryId, month),
): Mutation =
    upsert(
        EntityTypes.BUDGET_FUNDING,
        id,
        if (amount == null) {
            fields("month" to month.toString(), "incomeId" to incomeId, "categoryId" to categoryId, "amount" to null)
        } else {
            fields(
                "month" to month.toString(),
                "incomeId" to incomeId,
                "categoryId" to categoryId,
                "amount" to amount,
                "fundedBy" to fundedBy,
                "fundedAt" to fundedAt.toString(),
            )
        },
    )
