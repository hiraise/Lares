package app.lares.client.data.budget

import app.lares.client.data.MemberView
import app.lares.contract.budget.IncomeSummary
import app.lares.contract.budget.MonthSummary
import app.lares.contract.budget.TableCheck
import app.lares.contract.budget.TableDifferences
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.time.Instant

// What the budget repository exposes (architecture/budget.md § Models and views, plan M5-03).

/**
 * Everything the budget views are computed from. [receipts] are keyed by row id (extra incomes need it); [today] is
 * the date in the household [zone]; [viewer] is null when nobody is logged in.
 */
data class BudgetInputs(
    val plans: List<BudgetPlanData>,
    val monthRows: List<BudgetMonthData>,
    val receipts: Map<String, BudgetReceiptData>,
    val fundings: List<BudgetFundingData>,
    val members: List<MemberView>,
    val zone: TimeZone,
    val viewer: Viewer?,
    val today: LocalDate,
)

/** One month of «Все месяцы» (BUD-020). */
data class MonthRow(
    val month: YearMonth,
    val expected: Long,
    val actual: Long,
    val free: Long,
    val shortfall: Long,
    val current: Boolean,
)

/**
 * One month (BUD-011…BUD-014): its [table] (the month copy or the plan's), the [plan] version in force, totals and
 * sums; [differences] is null when the month follows its plan.
 */
data class MonthView(
    val month: YearMonth,
    val table: BudgetTable,
    val plan: BudgetPlanData,
    val summary: MonthSummary,
    val check: TableCheck,
    val differences: TableDifferences?,
    val canManage: Boolean,
    val isCurrent: Boolean,
)

/** Which income of a month: a planned one by its id in the table, an extra one by its receipt row id. */
sealed interface IncomeKey {
    data class Planned(
        val incomeId: String,
    ) : IncomeKey

    data class Extra(
        val receiptId: String,
    ) : IncomeKey
}

/** The income page: its summary and one row per used category with a cell for it, in table order (none for extra). */
data class IncomeView(
    val summary: IncomeSummary,
    val rows: List<FundingRow>,
)

/**
 * One category in an income's column. [funding] is the transferred amount, null when not ticked; [fundedBy] the name
 * of who ticked it; [changed] = the month changed this cell against the plan (BUD-013).
 */
data class FundingRow(
    val categoryId: String,
    val name: String,
    val planned: Long,
    val funding: Long?,
    val fundedBy: String?,
    val changed: Boolean,
)

/**
 * The plan in force for a month: [version] null before the first version (then [table] is empty); [editableFrom] is
 * the current month, the earliest month a save may apply from (BUD-004).
 */
data class PlanView(
    val version: BudgetPlanData?,
    val table: BudgetTable,
    val check: TableCheck,
    val editableFrom: YearMonth,
)

/** One entry of «История плана»; [current] = the version in force today. */
data class VersionRow(
    val effectiveFrom: YearMonth,
    val updatedBy: String?,
    val updatedAt: Instant,
    val current: Boolean,
)

/**
 * The «Сегодня» card (BUD-021): the next planned income whose actual amount is not recorded (null when all are) and
 * the number of categories with a planned cell of a recorded income that is not ticked.
 */
data class BudgetToday(
    val nextIncome: IncomeSummary?,
    val notFunded: Int,
)
