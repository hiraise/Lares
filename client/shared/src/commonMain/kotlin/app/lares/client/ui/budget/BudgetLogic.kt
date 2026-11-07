package app.lares.client.ui.budget

import app.lares.client.data.budget.FundingRow
import app.lares.client.resources.Res
import app.lares.client.resources.bud_amount_of
import app.lares.client.resources.bud_extra_income
import app.lares.client.resources.bud_hero_done
import app.lares.client.resources.bud_hero_expected
import app.lares.client.resources.bud_hero_free
import app.lares.client.resources.bud_hero_line
import app.lares.client.resources.bud_hero_over
import app.lares.client.resources.bud_hero_shortfall
import app.lares.client.resources.bud_income_all_distributed
import app.lares.client.resources.bud_income_came
import app.lares.client.resources.bud_income_came_amount
import app.lares.client.resources.bud_income_expected_state
import app.lares.client.resources.bud_income_free
import app.lares.client.resources.bud_income_over
import app.lares.client.resources.bud_income_shortfall
import app.lares.client.resources.bud_income_title
import app.lares.client.resources.bud_month_row
import app.lares.client.resources.bud_month_row_current
import app.lares.client.resources.date_day_month_short
import app.lares.client.resources.months_short
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.budget.IncomeSummary
import app.lares.contract.budget.MonthSummary
import app.lares.contract.budget.TableDifferences
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number

/** Texts of the budget screens (plan M5-04), pure so they are unit-tested. */
object BudgetLogic {
    /**
     * The hero of a month, by the precedence fixed in plan M5-04: free money, shortfall, more transferred than came
     * in, nothing recorded yet, all distributed.
     */
    fun hero(summary: MonthSummary): UiText =
        when {
            summary.free > 0 -> uiText(Res.string.bud_hero_free, raw(BudgetFormat.amount(summary.free)))
            summary.shortfall > 0 -> uiText(Res.string.bud_hero_shortfall, raw(BudgetFormat.amount(summary.shortfall)))
            summary.free < 0 -> uiText(Res.string.bud_hero_over, raw(BudgetFormat.amount(-summary.free)))
            summary.incomes.none { it.actual != null } -> uiText(Res.string.bud_hero_expected, raw(BudgetFormat.amount(summary.expected)))
            else -> uiText(Res.string.bud_hero_done)
        }

    /** «Пришло 150 000 из 346 000 · переведено 138 000». */
    fun heroLine(summary: MonthSummary): UiText =
        uiText(
            Res.string.bud_hero_line,
            raw(BudgetFormat.digits(summary.actual)),
            raw(BudgetFormat.digits(summary.expected)),
            raw(BudgetFormat.digits(summary.transferred)),
        )

    /** The month's shortfall shown in red under the hero when free money took the hero. */
    fun heroShortfall(summary: MonthSummary): UiText? =
        if (summary.free > 0 && summary.shortfall > 0) {
            uiText(Res.string.bud_hero_shortfall, raw(BudgetFormat.amount(summary.shortfall)))
        } else {
            null
        }

    /** «5 декабря · 162 000 ₽»; an extra income «Премия · 20 000 ₽». */
    fun incomeTitle(
        income: IncomeSummary,
        today: LocalDate,
    ): UiText {
        val date = income.plannedDate
        return if (date != null) {
            uiText(Res.string.bud_income_title, CalendarLogic.dayMonth(date, today), raw(BudgetFormat.amount(income.expected)))
        } else {
            val label = income.label?.let(::raw) ?: uiText(Res.string.bud_extra_income)
            uiText(Res.string.bud_income_title, label, raw(BudgetFormat.amount(income.actual ?: 0)))
        }
    }

    /** «Ожидается» or «Пришло 150 000 · 4 дек.». */
    fun incomeState(income: IncomeSummary): UiText {
        val actual = income.actual ?: return uiText(Res.string.bud_income_expected_state)
        val amount = raw(BudgetFormat.digits(actual))
        val date = income.actualDate ?: return uiText(Res.string.bud_income_came_amount, amount)
        return uiText(Res.string.bud_income_came, amount, shortDate(date))
    }

    /** «Недобор 12 000 ₽» of a recorded planned income that came short; null otherwise. */
    fun incomeShortfall(income: IncomeSummary): UiText? {
        val actual = income.actual ?: return null
        if (income.incomeId == null || actual >= income.expected) return null
        return uiText(Res.string.bud_income_shortfall, raw(BudgetFormat.amount(income.expected - actual)))
    }

    /** Free money of an income: «Не распределено 12 000 ₽», «Переведено больше, чем пришло: 3 000 ₽»; null before anything. */
    fun incomeFree(income: IncomeSummary): UiText? {
        if (income.actual == null && income.transferred == 0L) return null
        val free = (income.actual ?: 0) - income.transferred
        return when {
            free > 0 -> uiText(Res.string.bud_income_free, raw(BudgetFormat.amount(free)))
            free < 0 -> uiText(Res.string.bud_income_over, raw(BudgetFormat.amount(-free)))
            else -> uiText(Res.string.bud_income_all_distributed)
        }
    }

    /** The amount of a funding row: «27 000 ₽» planned or transferred as planned, «20 000 из 27 000» when changed. */
    fun fundingAmount(row: FundingRow): UiText {
        val funding = row.funding
        return if (funding == null || funding == row.planned) {
            raw(BudgetFormat.amount(row.planned))
        } else {
            uiText(Res.string.bud_amount_of, raw(BudgetFormat.digits(funding)), raw(BudgetFormat.digits(row.planned)))
        }
    }

    /** «45 000 из 60 000» of a category: funded of its monthly amount. */
    fun categoryAmount(
        funded: Long,
        amount: Long,
    ): UiText = uiText(Res.string.bud_amount_of, raw(BudgetFormat.digits(funded)), raw(BudgetFormat.digits(amount)))

    /** A category the month changed against its plan (BUD-013): itself, added, or one of its cells. */
    fun categoryChanged(
        categoryId: String,
        differences: TableDifferences?,
    ): Boolean =
        differences != null &&
            (
                categoryId in differences.changedCategories ||
                    categoryId in differences.addedCategories ||
                    differences.changedCells.any { it.first == categoryId }
            )

    /** «Декабрь 2026». */
    fun monthTitle(month: YearMonth): UiText = CalendarLogic.monthTitle(month.year, month.month.number)

    /** «Ожидается 346 000 · пришло 150 000» of «Все месяцы». */
    fun monthLine(
        expected: Long,
        actual: Long,
        current: Boolean = false,
    ): UiText =
        uiText(
            if (current) Res.string.bud_month_row_current else Res.string.bud_month_row,
            raw(BudgetFormat.digits(expected)),
            raw(BudgetFormat.digits(actual)),
        )

    /** The result of a month in «Все месяцы»: like the hero without the «expected» case. */
    fun monthResult(
        free: Long,
        shortfall: Long,
    ): UiText? =
        when {
            free > 0 -> uiText(Res.string.bud_hero_free, raw(BudgetFormat.amount(free)))
            shortfall > 0 -> uiText(Res.string.bud_hero_shortfall, raw(BudgetFormat.amount(shortfall)))
            free < 0 -> uiText(Res.string.bud_hero_over, raw(BudgetFormat.amount(-free)))
            else -> null
        }

    /** «4 дек.». */
    fun shortDate(date: LocalDate): UiText =
        uiText(Res.string.date_day_month_short, raw(date.day.toString()), UiText.Item(Res.array.months_short, date.month.ordinal))
}
