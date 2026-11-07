package app.lares.client.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.budget.BudgetRepository
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.MonthRow
import app.lares.client.data.budget.MonthView
import app.lares.contract.permissions.BudgetRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/**
 * The Budget screen and «Все месяцы» (plan M5-04). [month] is the month shown: the selected one when it lies between
 * the first month of the plan and the current month, else the current month. [view] is null without a plan in force
 * for it (the empty state).
 */
data class BudgetUiState(
    val loaded: Boolean = false,
    val month: YearMonth? = null,
    val current: YearMonth? = null,
    val today: LocalDate? = null,
    val view: MonthView? = null,
    val months: List<MonthRow> = emptyList(),
    val canManage: Boolean = false,
) {
    /** The month ‹ opens; null at the first month. */
    val previous: YearMonth?
        get() {
            val shown = month ?: return null
            val first = months.lastOrNull()?.month ?: return null
            return shown.minusMonth().takeIf { it >= first }
        }

    /** The month › opens; null at the current month. */
    val next: YearMonth?
        get() {
            val shown = month ?: return null
            val current = current ?: return null
            return shown.plusMonth().takeIf { it <= current }
        }
}

/**
 * Budget screen state over [BudgetRepository] (BUD-010, BUD-014, BUD-020). The selected month lives in the route's
 * saved state (it survives process death) and comes in through [show]; null = the current month.
 */
class BudgetViewModel(
    repository: BudgetRepository,
) : ViewModel() {
    private val selected = MutableStateFlow<YearMonth?>(null)

    val state: StateFlow<BudgetUiState> =
        combine(repository.observeInputs(), selected) { inputs, selected ->
            val months = BudgetViews.months(inputs)
            val current = inputs.today.yearMonth
            val first = months.lastOrNull()?.month
            val shown = selected?.takeIf { first != null && it >= first && it <= current } ?: current
            BudgetUiState(
                loaded = true,
                month = shown,
                current = current,
                today = inputs.today,
                view = BudgetViews.month(inputs, shown),
                months = months,
                canManage = inputs.viewer?.let(BudgetRules::canManage) == true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun show(month: YearMonth?) {
        selected.value = month
    }
}
