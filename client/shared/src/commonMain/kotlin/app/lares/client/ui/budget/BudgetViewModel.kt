package app.lares.client.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.budget.BudgetRepository
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.BudgetWriter
import app.lares.client.data.budget.MonthRow
import app.lares.client.data.budget.MonthView
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.validation.Validators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/** The «Добавить поступление» sheet (BUD-011): an optional label, the amount (required) and the date (default today). */
data class ExtraIncomeForm(
    val label: String = "",
    val amount: String = "",
    val date: LocalDate,
    val amountError: FieldError? = null,
)

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
    val extraForm: ExtraIncomeForm? = null,
    val error: UiError? = null,
) {
    /** The month has its own changes against the plan (BUD-013): the line under the hero, «Вернуть как в плане». */
    val monthChanged: Boolean get() = view?.differences?.isEmpty == false

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
    private val writer: BudgetWriter,
) : ViewModel() {
    private data class Local(
        val extraForm: ExtraIncomeForm? = null,
        val error: UiError? = null,
    )

    private val selected = MutableStateFlow<YearMonth?>(null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<BudgetUiState> =
        combine(repository.observeInputs(), selected, local) { inputs, selected, local ->
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
                extraForm = local.extraForm,
                error = local.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun show(month: YearMonth?) {
        selected.value = month
    }

    /** «Вернуть как в плане»: the shown month follows its plan again; ticks and receipts stay (BUD-013). */
    fun resetMonth() {
        val month = state.value.month ?: return
        write { writer.resetMonth(month) }
    }

    /** «Добавить поступление»: the sheet with today's date. */
    fun newExtra() {
        val state = state.value
        if (!state.canManage) return
        val today = state.today ?: return
        local.update { it.copy(extraForm = ExtraIncomeForm(date = today), error = null) }
    }

    fun onExtraLabel(value: String) = updateExtra { it.copy(label = value.take(Validators.BUDGET_LABEL_MAX)) }

    fun onExtraAmount(value: String) = updateExtra { it.copy(amount = BudgetFormat.input(value), amountError = null) }

    fun onExtraDate(date: LocalDate) = updateExtra { it.copy(date = date) }

    fun closeExtra() = local.update { it.copy(extraForm = null) }

    /** «Готово»: adds the extra income to the shown month; the amount is required. */
    fun saveExtra() {
        val form = local.value.extraForm ?: return
        val month = state.value.month ?: return
        val amount = BudgetFormat.parse(form.amount)?.takeIf { it > 0 }
        if (amount == null) {
            updateExtra { it.copy(amountError = FieldError.of(FieldErrorCode.REQUIRED)) }
            return
        }
        local.update { it.copy(extraForm = null) }
        write { writer.addExtraIncome(month, form.label.trim().ifEmpty { null }, amount, form.date) }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun updateExtra(change: (ExtraIncomeForm) -> ExtraIncomeForm) =
        local.update { l -> l.copy(extraForm = l.extraForm?.let(change)) }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(error = null) }
            } catch (e: LocalWriteException) {
                local.update { it.copy(error = UiError.Api(e.code)) }
            } catch (e: Exception) {
                local.update { it.copy(error = e.toUiError()) }
            }
        }
    }
}
