package app.lares.contract.entities

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Sync data of the budget module (architecture/budget.md § Contract data, ADR-0032). Amounts are whole roubles; no
// currency field. `YearMonth` is the ISO string on the wire ("2026-12"). New fields are only ever appended.

/** A row of the plan (BUD-001). */
@Serializable
data class BudgetCategory(
    /** UUIDv7, stable across versions and months; never reused. */
    val id: String,
    /** Trimmed, inner whitespace collapsed, 1..60; unique in the table, case-insensitive. */
    val name: String,
    /** Monthly amount, 0..1_000_000_000. */
    val amount: Long,
    /** Kept in the table (history, use again), left out of months and of every sum. */
    val unused: Boolean = false,
)

/** A column of the plan (BUD-002): an income expected on [day] of every month. */
@Serializable
data class BudgetIncome(
    /** UUIDv7, stable across versions. */
    val id: String,
    /** 1..31; on shorter months the last day (BudgetMath.incomeDate). */
    val day: Int,
    /** Expected, 0..1_000_000_000. */
    val amount: Long,
)

/** How much of an income goes to a category (BUD-003). Amount 1..1_000_000_000; zero cells are not stored. */
@Serializable
data class BudgetCell(
    val categoryId: String,
    val incomeId: String,
    val amount: Long,
)

/** The whole table of a plan version or of a month copy. Empty lists by default, so an empty plan decodes. */
@Serializable
data class BudgetTable(
    /** Display order; ≤ 100. */
    val categories: List<BudgetCategory> = emptyList(),
    /** ≤ 20; displayed by day, then list order. */
    val incomes: List<BudgetIncome> = emptyList(),
    /** One per (category, income) at most; cells of unused categories are kept but ignored by all sums. */
    val cells: List<BudgetCell> = emptyList(),
)

/** One plan version (BUD-004), in force from [effectiveFrom] until the next version. Row id: `Ids.budgetPlanRowId`. */
@Serializable
data class BudgetPlanData(
    /** Never changes. */
    val effectiveFrom: YearMonth,
    val table: BudgetTable,
    /** Member id of the last writer (always the writer). */
    val updatedBy: String,
    /** When it was saved last (the plan history). */
    val updatedAt: Instant,
)

/** The month-only edits of [month] (BUD-013). Row id: `Ids.budgetMonthRowId`. */
@Serializable
data class BudgetMonthData(
    /** Never changes. */
    val month: YearMonth,
    /** A full copy of the table; null = the month follows its plan version. */
    val table: BudgetTable? = null,
    /** Member id of the last writer (always the writer). */
    val updatedBy: String,
)

/**
 * The actual amount and date of an income in [month] (BUD-011). A planned income's row id is `Ids.budgetReceiptRowId`;
 * an extra income (bonus, [incomeId] null) has a random id and is the only budget row that can be deleted.
 */
@Serializable
data class BudgetReceiptData(
    /** Never changes. */
    val month: YearMonth,
    /** The planned income; null = extra income. Never changes. */
    val incomeId: String? = null,
    /** Extra incomes only: trimmed, blank -> null, ≤ 60. */
    val label: String? = null,
    /** Actual, 0..1_000_000_000; null = not recorded. */
    val amount: Long? = null,
    /** Actual date; required when [amount] is set. */
    val date: LocalDate? = null,
    /** Member id of the last writer (always the writer). */
    val updatedBy: String,
)

/** One tick (BUD-012): [categoryId] funded from [incomeId] in [month]. Row id: `Ids.budgetFundingRowId`. */
@Serializable
data class BudgetFundingData(
    /** Never changes. */
    val month: YearMonth,
    /** Never changes. */
    val incomeId: String,
    /** Never changes. */
    val categoryId: String,
    /** Transferred, 0..1_000_000_000; null = not funded (unticked). */
    val amount: Long? = null,
    /** The writer when [amount] is set; kept when unticked. */
    val fundedBy: String? = null,
    val fundedAt: Instant? = null,
)
