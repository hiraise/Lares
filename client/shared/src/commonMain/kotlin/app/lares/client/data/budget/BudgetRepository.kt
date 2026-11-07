package app.lares.client.data.budget

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MemberView
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.minuteTicks
import app.lares.client.data.observeViewer
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.Viewer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Read side of the budget (architecture/budget.md § Models and views): combines the four budget tables (the JSON
 * tables parsed once per emission), members, the household zone, the member's permissions and a minute ticker, and
 * computes the views with [BudgetViews]. [ticks] is [minuteTicks] of [clock]; tests pass their own. "Today" and the
 * current month roll over at midnight in the household zone.
 */
class BudgetRepository(
    private val localDb: LocalDb,
    private val members: MembersRepository,
    private val household: HouseholdRepository,
    private val permissions: PermissionsRepository,
    clock: Clock,
    private val ticks: Flow<Instant> = minuteTicks(clock),
) {
    private val db get() = localDb.db

    private fun rows() =
        combine(
            db.budget_planQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.budget_monthQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.budget_receiptQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.budget_fundingQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
        ) { plans, months, receipts, fundings ->
            Rows(
                plans.map { it.toData() },
                months.map { it.toData() },
                receipts.associate { it.id to it.toData() },
                fundings.map { it.toData() },
            )
        }

    /** All inputs of the views; a new value on every data change and every minute. */
    fun observeInputs(): Flow<BudgetInputs> {
        val context =
            combine(
                members.observeAll(),
                household.observe().map { householdZone(it?.data?.timeZone) },
                observeViewer(localDb, permissions),
            ) { allMembers, zone, viewer -> Context(allMembers, zone, viewer) }
        return combine(rows(), context, ticks) { rows, ctx, now ->
            BudgetInputs(
                plans = rows.plans,
                monthRows = rows.months,
                receipts = rows.receipts,
                fundings = rows.fundings,
                members = ctx.members,
                zone = ctx.zone,
                viewer = ctx.viewer,
                today = now.toLocalDateTime(ctx.zone).date,
            )
        }
    }

    fun observeMonths(): Flow<List<MonthRow>> = observeInputs().map(BudgetViews::months).distinctUntilChanged()

    fun observeMonth(month: YearMonth): Flow<MonthView?> = observeInputs().map { BudgetViews.month(it, month) }.distinctUntilChanged()

    fun observeIncome(
        month: YearMonth,
        key: IncomeKey,
    ): Flow<IncomeView?> = observeInputs().map { BudgetViews.income(it, month, key) }.distinctUntilChanged()

    fun observePlan(from: YearMonth): Flow<PlanView> = observeInputs().map { BudgetViews.plan(it, from) }.distinctUntilChanged()

    fun observeHistory(): Flow<List<VersionRow>> = observeInputs().map(BudgetViews::history).distinctUntilChanged()

    fun observeToday(): Flow<BudgetToday?> = observeInputs().map(BudgetViews::today).distinctUntilChanged()

    /**
     * True when a category may not be deleted from the plan, only left unused (BUD-001): a version in force before
     * [before], a month copy or a tick references it.
     */
    suspend fun isCategoryUsed(
        categoryId: String,
        before: YearMonth,
    ): Boolean =
        localDb.read {
            tables(before).any { table -> table.categories.any { it.id == categoryId } } ||
                fundings().any { it.categoryId == categoryId }
        }

    /** True when an income may not be deleted from the plan: an earlier version, a month copy, a receipt or a tick uses it. */
    suspend fun isIncomeUsed(
        incomeId: String,
        before: YearMonth,
    ): Boolean =
        localDb.read {
            tables(before).any { table -> table.incomes.any { it.id == incomeId } } ||
                budget_receiptQueries.selectAll().executeAsList().any { it.income_id == incomeId } ||
                fundings().any { it.incomeId == incomeId }
        }

    /** Tables of the versions in force before [before] and of every month copy. */
    private fun LaresDb.tables(before: YearMonth): Sequence<BudgetTable> =
        budget_planQueries.selectAll().executeAsList().asSequence().filter { YearMonth.parse(it.effective_from) < before }.map {
            decodeTable(it.plan_table)
        } +
            budget_monthQueries
                .selectAll()
                .executeAsList()
                .asSequence()
                .mapNotNull { row -> row.month_table?.let(::decodeTable) }

    private fun LaresDb.fundings(): List<BudgetFundingData> = budget_fundingQueries.selectAll().executeAsList().map { it.toData() }

    private data class Rows(
        val plans: List<BudgetPlanData>,
        val months: List<BudgetMonthData>,
        val receipts: Map<String, BudgetReceiptData>,
        val fundings: List<BudgetFundingData>,
    )

    private data class Context(
        val members: List<MemberView>,
        val zone: TimeZone,
        val viewer: Viewer?,
    )
}
