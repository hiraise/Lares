package app.lares.contract.budget

import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.onDay

/**
 * The calculations every budget screen is built on (architecture/budget.md § Logic, ADR-0032). Pure; the server never
 * calls it. Unused categories are left out of every sum; cells, receipts and ticks of what is not in the month's table
 * are ignored.
 */
object BudgetMath {
    /** The version in force for [month] (BUD-004): the greatest `effectiveFrom` not after it; null before the first one. */
    fun planFor(
        month: YearMonth,
        plans: Collection<BudgetPlanData>,
    ): BudgetPlanData? = plans.filter { it.effectiveFrom <= month }.maxByOrNull { it.effectiveFrom }

    /** The first month of the budget (BUD-010): months exist from the first version on. */
    fun firstMonth(plans: Collection<BudgetPlanData>): YearMonth? = plans.minOfOrNull { it.effectiveFrom }

    /** The table of [month]: its own copy in [monthRow] (BUD-013) if there is one, else that of its plan version. */
    fun tableFor(
        month: YearMonth,
        plans: Collection<BudgetPlanData>,
        monthRow: BudgetMonthData?,
    ): BudgetTable? = monthRow?.table ?: planFor(month, plans)?.table

    /** The date of an income's [day] in [month]; days past the month's end are its last day (BUD-002). */
    fun incomeDate(
        month: YearMonth,
        day: Int,
    ): LocalDate = month.onDay(day.coerceIn(1, month.numberOfDays))

    /** Row and column sums of the used categories and the ones that do not add up (BUD-003). */
    fun check(table: BudgetTable): TableCheck {
        val used = table.categories.filterNot { it.unused }
        val rows = used.associateTo(LinkedHashMap()) { it.id to 0L }
        val columns = table.incomes.associateTo(LinkedHashMap()) { it.id to 0L }
        for (cell in table.cells) {
            val row = rows[cell.categoryId] ?: continue
            val column = columns[cell.incomeId] ?: continue
            rows[cell.categoryId] = row + cell.amount
            columns[cell.incomeId] = column + cell.amount
        }
        return TableCheck(
            rows = rows,
            columns = columns,
            badRows = used.filter { rows[it.id] != it.amount }.mapTo(LinkedHashSet()) { it.id },
            badColumns = table.incomes.filter { columns[it.id] != it.amount }.mapTo(LinkedHashSet()) { it.id },
            expectedIncome = table.incomes.sumOf { it.amount },
            categoriesTotal = used.sumOf { it.amount },
        )
    }

    /**
     * What [monthTable] changed against [planTable] (BUD-013), compared by ids. A category used in one table and missing
     * or unused in the other is added or removed; the same used category with another name or amount is changed. Cells
     * compare the amounts of used categories and present incomes, so the cells of an added category are changed too.
     */
    fun differences(
        monthTable: BudgetTable,
        planTable: BudgetTable,
    ): TableDifferences {
        val monthCategories = monthTable.categories.filterNot { it.unused }.associateBy { it.id }
        val planCategories = planTable.categories.filterNot { it.unused }.associateBy { it.id }
        val monthIncomes = monthTable.incomes.associateBy { it.id }
        val planIncomes = planTable.incomes.associateBy { it.id }
        val monthCells = usedCells(monthTable)
        val planCells = usedCells(planTable)
        return TableDifferences(
            changedCells = (monthCells.keys + planCells.keys).filterTo(LinkedHashSet()) { monthCells[it] != planCells[it] },
            addedCategories = monthCategories.keys - planCategories.keys,
            removedCategories = planCategories.keys - monthCategories.keys,
            changedCategories =
                monthCategories.values
                    .filter { category ->
                        val plan = planCategories[category.id]
                        plan != null && (plan.name != category.name || plan.amount != category.amount)
                    }.mapTo(LinkedHashSet()) { it.id },
            addedIncomes = monthIncomes.keys - planIncomes.keys,
            removedIncomes = planIncomes.keys - monthIncomes.keys,
            changedIncomes =
                monthIncomes.values
                    .filter { income ->
                        val plan = planIncomes[income.id]
                        plan != null && (plan.day != income.day || plan.amount != income.amount)
                    }.mapTo(LinkedHashSet()) { it.id },
        )
    }

    /**
     * Totals of [month] with [table] (BUD-011, BUD-012, BUD-014). [receipts] are keyed by row id; receipts and
     * [fundings] of other months are ignored. A tick counts only if its income and category are in the table and the
     * category is used; `transferred` sums the ticked amounts. An income without a recorded amount adds nothing to
     * `actual`, `shortfall` or `free`, but its ticks count toward `transferred`, so `free` can be negative.
     */
    fun summary(
        month: YearMonth,
        table: BudgetTable,
        receipts: Map<String, BudgetReceiptData>,
        fundings: Collection<BudgetFundingData>,
    ): MonthSummary {
        val used = table.categories.filterNot { it.unused }
        val usedIds = used.mapTo(HashSet()) { it.id }
        val incomeIds = table.incomes.mapTo(HashSet()) { it.id }
        val ticks =
            fundings
                .filter { it.month == month && it.amount != null && it.incomeId in incomeIds && it.categoryId in usedIds }
                .associateBy { it.incomeId to it.categoryId }
        val cells = table.cells.filter { it.categoryId in usedIds && it.incomeId in incomeIds }
        val monthReceipts = receipts.filterValues { it.month == month }
        val planned =
            table.incomes
                .map { income ->
                    val receipt = monthReceipts.entries.firstOrNull { it.value.incomeId == income.id }
                    val incomeCells = cells.filter { it.incomeId == income.id }
                    IncomeSummary(
                        incomeId = income.id,
                        receiptId = receipt?.key,
                        label = null,
                        plannedDate = incomeDate(month, income.day),
                        expected = income.amount,
                        actual = receipt?.value?.amount,
                        actualDate = receipt?.value?.date,
                        transferred = ticks.values.filter { it.incomeId == income.id }.sumOf { it.amount ?: 0L },
                        fundedCount = incomeCells.count { (it.incomeId to it.categoryId) in ticks },
                        plannedCount = incomeCells.size,
                    )
                }.sortedBy { it.plannedDate }
        val extra =
            monthReceipts.entries
                .filter { it.value.incomeId == null }
                .sortedWith(compareBy({ it.value.date == null }, { it.value.date }, { it.key }))
                .map { (id, receipt) ->
                    IncomeSummary(
                        incomeId = null,
                        receiptId = id,
                        label = receipt.label,
                        plannedDate = null,
                        expected = 0,
                        actual = receipt.amount,
                        actualDate = receipt.date,
                        transferred = 0,
                        fundedCount = 0,
                        plannedCount = 0,
                    )
                }
        val incomes = planned + extra
        val actual = incomes.sumOf { it.actual ?: 0L }
        val transferred = ticks.values.sumOf { it.amount ?: 0L }
        return MonthSummary(
            incomes = incomes,
            categories = used.map { categorySummary(it, cells.filter { cell -> cell.categoryId == it.id }, ticks) },
            expected = planned.sumOf { it.expected },
            actual = actual,
            transferred = transferred,
            shortfall = planned.sumOf { income -> income.actual?.let { (income.expected - it).coerceAtLeast(0) } ?: 0L },
            free = actual - transferred,
        )
    }

    private fun categorySummary(
        category: BudgetCategory,
        cells: List<BudgetCell>,
        ticks: Map<Pair<String, String>, BudgetFundingData>,
    ): CategorySummary =
        CategorySummary(
            categoryId = category.id,
            name = category.name,
            amount = category.amount,
            planned = cells.sumOf { it.amount },
            funded = ticks.values.filter { it.categoryId == category.id }.sumOf { it.amount ?: 0L },
            complete = cells.isNotEmpty() && cells.all { (it.incomeId to it.categoryId) in ticks },
        )

    /** (categoryId, incomeId) → amount of the cells of used categories and present incomes. */
    private fun usedCells(table: BudgetTable): Map<Pair<String, String>, Long> {
        val used = table.categories.filterNot { it.unused }.mapTo(HashSet()) { it.id }
        val incomes = table.incomes.mapTo(HashSet()) { it.id }
        return table.cells
            .filter { it.categoryId in used && it.incomeId in incomes }
            .associate { (it.categoryId to it.incomeId) to it.amount }
    }
}

/** Sums of a table (BUD-003); [badRows] and [badColumns] hold ids. */
data class TableCheck(
    /** Used category id → sum of its cells, in table order. */
    val rows: Map<String, Long>,
    /** Income id → sum of the cells of used categories, in table order. */
    val columns: Map<String, Long>,
    /** Used categories whose row sum ≠ their monthly amount. */
    val badRows: Set<String>,
    /** Incomes whose column sum ≠ their expected amount. */
    val badColumns: Set<String>,
    /** Σ expected incomes. */
    val expectedIncome: Long,
    /** Σ monthly amounts of used categories. */
    val categoriesTotal: Long,
)

/** What a month changed against its plan version (BUD-013). */
data class TableDifferences(
    /** (categoryId, incomeId) with a different amount, added or removed. */
    val changedCells: Set<Pair<String, String>>,
    /** Used in the month and missing or unused in the plan. */
    val addedCategories: Set<String>,
    /** Used in the plan and missing or unused in the month. */
    val removedCategories: Set<String>,
    /** Used in both with a different name or monthly amount. */
    val changedCategories: Set<String>,
    val addedIncomes: Set<String>,
    val removedIncomes: Set<String>,
    /** In both with a different day or expected amount. */
    val changedIncomes: Set<String>,
) {
    val isEmpty: Boolean
        get() =
            changedCells.isEmpty() &&
                addedCategories.isEmpty() &&
                removedCategories.isEmpty() &&
                changedCategories.isEmpty() &&
                addedIncomes.isEmpty() &&
                removedIncomes.isEmpty() &&
                changedIncomes.isEmpty()
}

/** Totals of a month (BUD-014). */
data class MonthSummary(
    /** Planned incomes by planned date, then table order; then extra incomes by actual date, then receipt id. */
    val incomes: List<IncomeSummary>,
    /** Used categories in table order. */
    val categories: List<CategorySummary>,
    val expected: Long,
    val actual: Long,
    val transferred: Long,
    /** Σ max(0, expected − actual) over planned incomes whose actual amount is recorded. */
    val shortfall: Long,
    /** actual − transferred; negative when more was transferred than came in. */
    val free: Long,
)

/** One income of a month; an extra income has no [incomeId], [plannedDate] or cells and [expected] 0. */
data class IncomeSummary(
    val incomeId: String?,
    /** The receipt row, null when nothing was recorded for a planned income. */
    val receiptId: String?,
    val label: String?,
    val plannedDate: LocalDate?,
    val expected: Long,
    /** Null = not recorded. */
    val actual: Long?,
    val actualDate: LocalDate?,
    val transferred: Long,
    /** Planned cells of this income that are ticked. */
    val fundedCount: Int,
    /** Cells of used categories in this income's column. */
    val plannedCount: Int,
)

/** One used category of a month (BUD-012). */
data class CategorySummary(
    val categoryId: String,
    val name: String,
    /** Monthly amount. */
    val amount: Long,
    /** Σ its cells. */
    val planned: Long,
    /** Σ its ticked amounts. */
    val funded: Long,
    /** Every planned cell is ticked; false for a category without cells. */
    val complete: Boolean,
)
