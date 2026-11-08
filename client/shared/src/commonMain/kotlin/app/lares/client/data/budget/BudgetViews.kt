package app.lares.client.data.budget

import app.lares.contract.budget.BudgetMath
import app.lares.contract.budget.MonthSummary
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.BudgetRules
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.yearMonth

/**
 * The budget views (architecture/budget.md § Models and views): pure functions of [BudgetInputs], built on
 * [BudgetMath]. "Current month" is the month of `inputs.today`.
 */
object BudgetViews {
    /** Months from the first version to the current one, newest first; future versions add no months (BUD-010). */
    fun months(inputs: BudgetInputs): List<MonthRow> {
        val first = BudgetMath.firstMonth(inputs.plans) ?: return emptyList()
        val current = inputs.today.yearMonth
        val rows = mutableListOf<MonthRow>()
        var month = current
        while (month >= first) {
            val summary = summary(inputs, month)
            if (summary != null) {
                rows += MonthRow(month, summary.expected, summary.actual, summary.free, summary.shortfall, month == current)
            }
            month = month.minusMonth()
        }
        return rows
    }

    /** One month, also a future one (the plan screen previews it); null before the first version. */
    fun month(
        inputs: BudgetInputs,
        month: YearMonth,
    ): MonthView? {
        val plan = BudgetMath.planFor(month, inputs.plans) ?: return null
        val monthTable = monthRow(inputs, month)?.table
        val table = monthTable ?: plan.table
        return MonthView(
            month = month,
            table = table,
            plan = plan,
            summary = BudgetMath.summary(month, table, inputs.receipts, inputs.fundings),
            check = BudgetMath.check(table),
            differences = monthTable?.let { BudgetMath.differences(it, plan.table) },
            canManage = inputs.viewer?.let(BudgetRules::canManage) == true,
            isCurrent = month == inputs.today.yearMonth,
        )
    }

    /** The income page; null when the month or the income is not there. */
    fun income(
        inputs: BudgetInputs,
        month: YearMonth,
        key: IncomeKey,
    ): IncomeView? {
        val view = month(inputs, month) ?: return null
        val summary =
            view.summary.incomes.firstOrNull {
                when (key) {
                    is IncomeKey.Planned -> it.incomeId == key.incomeId
                    is IncomeKey.Extra -> it.incomeId == null && it.receiptId == key.receiptId
                }
            } ?: return null
        val incomeId = summary.incomeId ?: return IncomeView(summary, emptyList())
        val cells =
            view.table.cells
                .filter { it.incomeId == incomeId }
                .associateBy { it.categoryId }
        val fundings = inputs.fundings.filter { it.month == month && it.incomeId == incomeId }.associateBy { it.categoryId }
        val changed = view.differences?.changedCells.orEmpty()
        val rows =
            view.table.categories
                .filter { !it.unused && it.id in cells }
                .map { category ->
                    val funding = fundings[category.id]?.takeIf { it.amount != null }
                    FundingRow(
                        categoryId = category.id,
                        name = category.name,
                        planned = cells.getValue(category.id).amount,
                        funding = funding?.amount,
                        fundedBy = funding?.fundedBy?.let { id -> inputs.members.firstOrNull { it.id == id }?.name },
                        changed = (category.id to incomeId) in changed,
                    )
                }
        return IncomeView(summary, rows)
    }

    /** The version in force for [from] and its table; an empty table before the first version. */
    fun plan(
        inputs: BudgetInputs,
        from: YearMonth,
    ): PlanView {
        val version = BudgetMath.planFor(from, inputs.plans)
        val table = version?.table ?: BudgetTable()
        return PlanView(version, table, BudgetMath.check(table), inputs.today.yearMonth)
    }

    /** Plan versions newest first (BUD-004). */
    fun history(inputs: BudgetInputs): List<VersionRow> {
        val current = BudgetMath.planFor(inputs.today.yearMonth, inputs.plans)?.effectiveFrom
        return inputs.plans
            .sortedByDescending { it.effectiveFrom }
            .map { plan ->
                VersionRow(
                    effectiveFrom = plan.effectiveFrom,
                    updatedBy = inputs.members.firstOrNull { it.id == plan.updatedBy }?.name,
                    updatedAt = plan.updatedAt,
                    current = plan.effectiveFrom == current,
                )
            }
    }

    /** The «Сегодня» card of the current month (BUD-021); null without a plan in force. */
    fun today(inputs: BudgetInputs): BudgetToday? {
        val view = month(inputs, inputs.today.yearMonth) ?: return null
        val unrecorded = view.summary.incomes.filter { it.incomeId != null && it.actual == null }
        val next = unrecorded.firstOrNull { income -> income.plannedDate?.let { it >= inputs.today } == true } ?: unrecorded.firstOrNull()
        val recorded = view.summary.incomes.mapNotNullTo(HashSet()) { income -> income.incomeId.takeIf { income.actual != null } }
        val used =
            view.table.categories
                .filterNot { it.unused }
                .mapTo(HashSet()) { it.id }
        val ticked =
            inputs.fundings
                .filter { it.month == view.month && it.amount != null }
                .mapTo(HashSet()) { it.categoryId to it.incomeId }
        val notFunded =
            view.table.cells
                .filter { it.categoryId in used && it.incomeId in recorded && (it.categoryId to it.incomeId) !in ticked }
                .mapTo(HashSet()) { it.categoryId }
                .size
        return BudgetToday(next, notFunded)
    }

    private fun monthRow(
        inputs: BudgetInputs,
        month: YearMonth,
    ) = inputs.monthRows.firstOrNull { it.month == month }

    private fun summary(
        inputs: BudgetInputs,
        month: YearMonth,
    ): MonthSummary? {
        val table = BudgetMath.tableFor(month, inputs.plans, monthRow(inputs, month)) ?: return null
        return BudgetMath.summary(month, table, inputs.receipts, inputs.fundings)
    }
}
