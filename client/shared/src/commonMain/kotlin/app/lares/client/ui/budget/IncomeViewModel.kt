package app.lares.client.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.budget.BudgetRepository
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.BudgetWriter
import app.lares.client.data.budget.FundingRow
import app.lares.client.data.budget.IncomeKey
import app.lares.client.data.budget.IncomeView
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.toUiError
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.validation.Validators
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Koin parameters of [IncomeViewModel]: a planned income [incomeId] of [month], or an extra income by its [receiptId]
 * (M5-06).
 */
data class IncomeArgs(
    val month: YearMonth,
    val incomeId: String?,
    val receiptId: String? = null,
) {
    val key: IncomeKey get() = incomeId?.let { IncomeKey.Planned(it) } ?: IncomeKey.Extra(requireNotNull(receiptId))
    val isExtra: Boolean get() = incomeId == null
}

/** The «Перевели» sheet: the transferred amount of one category, typed as digits. */
data class FundingSheet(
    val categoryId: String,
    val name: String,
    val planned: Long,
    val text: String,
)

/**
 * The income page (BUD-011, BUD-012, BUD-014). [amountText] and [date] (and [labelText] of an extra income) are the
 * fields: while [editing] what is typed, else the recorded receipt. [view] is null when the income is not in the month
 * (any more); [deleted] = this page deleted its extra income.
 */
data class IncomeUiState(
    val loaded: Boolean = false,
    val extra: Boolean = false,
    val view: IncomeView? = null,
    val labelText: String = "",
    val deleted: Boolean = false,
    val today: LocalDate? = null,
    val canManage: Boolean = false,
    val amountText: String = "",
    val date: LocalDate? = null,
    val editing: Boolean = false,
    val sheet: FundingSheet? = null,
    val error: UiError? = null,
) {
    val toFund: List<FundingRow> get() = view?.rows.orEmpty().filter { it.funding == null }
    val funded: List<FundingRow> get() = view?.rows.orEmpty().filter { it.funding != null }
}

/**
 * Records what came in and ticks the categories transferred from it. A tick writes at once (like shopping); the
 * fields are written on «Готово» of the keyboard, when the amount field loses focus and when a date is picked.
 */
class IncomeViewModel(
    repository: BudgetRepository,
    private val writer: BudgetWriter,
    private val args: IncomeArgs,
) : ViewModel() {
    private data class Local(
        val labelText: String = "",
        val deleted: Boolean = false,
        val amountText: String = "",
        val date: LocalDate? = null,
        val editing: Boolean = false,
        val sheet: FundingSheet? = null,
        val error: UiError? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<IncomeUiState> =
        combine(repository.observeInputs(), local) { inputs, local ->
            val view = BudgetViews.income(inputs, args.month, args.key)
            val summary = view?.summary
            IncomeUiState(
                loaded = true,
                extra = args.isExtra,
                view = view,
                labelText = if (local.editing) local.labelText else summary?.label.orEmpty(),
                deleted = local.deleted,
                today = inputs.today,
                canManage = inputs.viewer?.let(BudgetRules::canManage) == true,
                amountText = if (local.editing) local.amountText else summary?.actual?.toString().orEmpty(),
                date = if (local.editing) local.date else summary?.actualDate,
                editing = local.editing,
                sheet = local.sheet,
                error = local.error,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, IncomeUiState())

    /** First edit of the fields: an unrecorded income starts with its expected amount and today (plan M5-04). */
    fun startEdit() {
        val current = state.value
        // The local flag, not the state: the state may not show an edit started a moment ago.
        if (local.value.editing || !current.canManage) return
        val summary = current.view?.summary ?: return
        local.update {
            it.copy(
                labelText = summary.label.orEmpty(),
                amountText = (summary.actual ?: summary.expected).toString(),
                date = summary.actualDate ?: current.today,
                editing = true,
                error = null,
            )
        }
    }

    fun changeAmount(text: String) {
        startEdit()
        local.update { it.copy(amountText = BudgetFormat.input(text), editing = true) }
    }

    /** The label of an extra income (optional). */
    fun changeLabel(text: String) {
        startEdit()
        local.update { it.copy(labelText = text.take(Validators.BUDGET_LABEL_MAX), editing = true) }
    }

    /**
     * Writes the fields: an empty amount clears the receipt (= not recorded). An extra income needs an amount: an empty
     * one is not written and the field shows the stored amount again.
     */
    fun commit() {
        val current = local.value
        if (!current.editing) return
        val amount = BudgetFormat.parse(current.amountText)
        val date = current.date ?: state.value.today ?: return
        val incomeId = args.incomeId
        if (incomeId == null) {
            val id = args.receiptId ?: return
            val label = current.labelText.trim().ifEmpty { null }
            if (amount == null || amount <= 0) {
                local.update { it.copy(editing = false) }
                return
            }
            write(after = { local.update { it.copy(editing = false) } }) { writer.updateExtraIncome(id, label, amount, date) }
            return
        }
        write(after = { local.update { it.copy(editing = false) } }) {
            if (amount == null) {
                writer.clearIncome(args.month, incomeId)
            } else {
                writer.recordIncome(args.month, incomeId, amount, date)
            }
        }
    }

    /** «Удалить» of an extra income (BUD-011); the page then closes. */
    fun delete() {
        val id = args.receiptId ?: return
        local.update { it.copy(editing = false) }
        write(after = { local.update { it.copy(deleted = true) } }) { writer.deleteExtraIncome(id) }
    }

    fun pickDate(date: LocalDate) {
        startEdit()
        local.update { it.copy(date = date) }
        commit()
    }

    /** «Очистить»: the receipt is reset; ticks stay. */
    fun clear() {
        val incomeId = args.incomeId ?: return
        local.update { it.copy(editing = false, amountText = "", date = null) }
        write { writer.clearIncome(args.month, incomeId) }
    }

    /** A tick writes the planned amount; an untick clears it. */
    fun toggle(row: FundingRow) {
        val incomeId = args.incomeId ?: return
        write {
            if (row.funding == null) {
                writer.fund(args.month, incomeId, row.categoryId, row.planned)
            } else {
                writer.unfund(args.month, incomeId, row.categoryId)
            }
        }
    }

    fun fundAll() {
        val incomeId = args.incomeId ?: return
        write { writer.fundAll(args.month, incomeId) }
    }

    fun openSheet(row: FundingRow) {
        if (!state.value.canManage) return
        local.update { it.copy(sheet = FundingSheet(row.categoryId, row.name, row.planned, (row.funding ?: row.planned).toString())) }
    }

    fun changeSheet(text: String) = local.update { it.copy(sheet = it.sheet?.copy(text = BudgetFormat.input(text))) }

    fun closeSheet() = local.update { it.copy(sheet = null) }

    /** «Готово» of the sheet: the typed amount is transferred; an empty one unticks. */
    fun saveSheet() {
        val sheet = local.value.sheet ?: return
        val incomeId = args.incomeId ?: return
        local.update { it.copy(sheet = null) }
        val amount = BudgetFormat.parse(sheet.text)
        write {
            if (amount == null) {
                writer.unfund(args.month, incomeId, sheet.categoryId)
            } else {
                writer.fund(args.month, incomeId, sheet.categoryId, amount)
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    /** Writes finish even when the page is left right after (the view model's scope is cancelled then). */
    private fun write(
        after: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { block() }
                local.update { it.copy(error = null) }
            } catch (e: LocalWriteException) {
                local.update { it.copy(error = UiError.Api(e.code)) }
            } catch (e: Exception) {
                local.update { it.copy(error = e.toUiError()) }
            } finally {
                after()
            }
        }
    }
}
