package app.lares.client.ui.budget.plan

import app.lares.client.data.budget.VersionRow
import app.lares.client.resources.Res
import app.lares.client.resources.bud_plan_cell_label
import app.lares.client.resources.bud_plan_distributed
import app.lares.client.resources.bud_plan_history_line
import app.lares.client.resources.bud_plan_history_title
import app.lares.client.resources.bud_plan_income_day
import app.lares.client.resources.bud_plan_income_row
import app.lares.client.resources.bud_plan_mismatch
import app.lares.client.resources.bud_plan_save_from
import app.lares.client.resources.bud_plan_save_from_current
import app.lares.client.resources.bud_plan_summary
import app.lares.client.resources.bud_plan_version_title
import app.lares.client.resources.months
import app.lares.client.ui.budget.BudgetFormat
import app.lares.client.ui.budget.BudgetLogic
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.budget.TableCheck
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetTable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/** Pure helpers and texts of the plan screen (plan M5-05). */
object PlanLogic {
    /** Incomes by day, then table order (BUD-002). */
    fun incomesByDay(table: BudgetTable): List<BudgetIncome> =
        table.incomes
            .withIndex()
            .sortedWith(compareBy({ it.value.day }, { it.index }))
            .map { it.value }

    /** A category name as the normalizer stores it: trimmed, inner whitespace collapsed. */
    fun collapse(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    /**
     * [categories] with [id] swapped with its previous ([up]) or next neighbour of the same group (used or unused);
     * null when it cannot move.
     */
    fun move(
        categories: List<BudgetCategory>,
        id: String,
        up: Boolean,
    ): List<BudgetCategory>? {
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) return null
        val unused = categories[index].unused
        val range = if (up) (index - 1 downTo 0) else (index + 1 until categories.size)
        val other = range.firstOrNull { categories[it].unused == unused } ?: return null
        return categories.toMutableList().apply {
            this[index] = categories[other]
            this[other] = categories[index]
        }
    }

    /** True when the category may move [up] (or down) within its group. */
    fun canMove(
        categories: List<BudgetCategory>,
        id: String,
        up: Boolean,
    ): Boolean = move(categories, id, up) != null

    /** «Доход 346 000 · Категории 346 000». */
    fun summary(check: TableCheck): UiText =
        uiText(Res.string.bud_plan_summary, raw(BudgetFormat.digits(check.expectedIncome)), raw(BudgetFormat.digits(check.categoriesTotal)))

    /** «не сходится на 6 000» when the incomes and the categories differ, else null. */
    fun mismatch(check: TableCheck): UiText? {
        val difference = abs(check.expectedIncome - check.categoriesTotal)
        return if (difference == 0L) null else uiText(Res.string.bud_plan_mismatch, raw(BudgetFormat.digits(difference)))
    }

    /** True when every row and column adds up and incomes equal categories (the ✓ of the summary bar). */
    fun balanced(check: TableCheck): Boolean =
        check.badRows.isEmpty() && check.badColumns.isEmpty() && check.expectedIncome == check.categoriesTotal

    /** «5-е число · 162 000 ₽». */
    fun incomeRow(income: BudgetIncome): UiText =
        uiText(Res.string.bud_plan_income_row, raw(income.day.toString()), raw(BudgetFormat.amount(income.amount)))

    /** «5-е». */
    fun incomeDay(income: BudgetIncome): UiText = uiText(Res.string.bud_plan_income_day, raw(income.day.toString()))

    /** «5-е · из 162 000» above a cell field of the category sheet. */
    fun cellLabel(income: BudgetIncome): UiText =
        uiText(Res.string.bud_plan_cell_label, incomeDay(income), raw(BudgetFormat.digits(income.amount)))

    /** «Распределено 58 000 из 60 000». */
    fun distributed(
        sum: Long,
        of: Long,
    ): UiText = uiText(Res.string.bud_plan_distributed, raw(BudgetFormat.digits(sum)), raw(BudgetFormat.digits(of)))

    /** «План с января 2027». */
    fun versionTitle(from: YearMonth): UiText = uiText(Res.string.bud_plan_version_title, genitive(from), raw(from.year.toString()))

    /** «С января 2027» in the history. */
    fun historyTitle(row: VersionRow): UiText =
        uiText(Res.string.bud_plan_history_title, genitive(row.effectiveFrom), raw(row.effectiveFrom.year.toString()))

    /** «Ольга · 7 нояб.»; the date in the household zone. */
    fun historyLine(
        row: VersionRow,
        zone: TimeZone,
    ): UiText {
        val date = BudgetLogic.shortDate(row.updatedAt.toLocalDateTime(zone).date)
        val by = row.updatedBy ?: return date
        return uiText(Res.string.bud_plan_history_line, raw(by), date)
    }

    /** «С января» or «С декабря (текущий месяц)» in the save dialog. */
    fun saveOption(
        month: YearMonth,
        current: YearMonth?,
    ): UiText = uiText(if (month == current) Res.string.bud_plan_save_from_current else Res.string.bud_plan_save_from, genitive(month))

    /** The month name as after «с» / «of» («января»). */
    fun genitive(month: YearMonth): UiText = UiText.Item(Res.array.months, month.month.number - 1)
}
