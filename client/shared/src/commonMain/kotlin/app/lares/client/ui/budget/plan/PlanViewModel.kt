package app.lares.client.ui.budget.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.budget.BudgetInputs
import app.lares.client.data.budget.BudgetRepository
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.BudgetWriter
import app.lares.client.resources.Res
import app.lares.client.resources.bud_plan_day_error
import app.lares.client.resources.bud_plan_name_taken
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.budget.BudgetFormat
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.toUiError
import app.lares.contract.Ids
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.budget.BudgetMath
import app.lares.contract.budget.TableCheck
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.validation.Validators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/**
 * Koin parameters of [PlanViewModel] (plan M5-05): no arguments = edit the plan; [version] = look at that plan version
 * read-only (from «История плана»); [month] = month mode (only that month's copy, wired in M5-06).
 */
data class PlanArgs(
    val version: YearMonth? = null,
    val month: YearMonth? = null,
)

enum class PlanTab { CATEGORIES, INCOMES, TABLE }

/**
 * The category sheet: [id] null for a new category; amounts typed as digits, [cells] by income id (empty = no cell).
 * [deletable] = no earlier version, month copy or tick uses the category (BUD-001; null while checking); else only
 * «Не использовать».
 */
data class CategoryForm(
    val id: String?,
    val name: String = "",
    val amount: String = "",
    val cells: Map<String, String> = emptyMap(),
    val unused: Boolean = false,
    val deletable: Boolean? = null,
    val nameError: FieldError? = null,
) {
    val amountValue: Long get() = BudgetFormat.parse(amount) ?: 0
    val distributed: Long get() = cells.values.sumOf { BudgetFormat.parse(it) ?: 0 }
}

/** The income sheet: day and expected amount typed; [deletable] = nothing uses it (null while checking). */
data class IncomeForm(
    val id: String,
    val isNew: Boolean,
    val day: String,
    val amount: String,
    val deletable: Boolean? = null,
    val dayError: FieldError? = null,
)

/**
 * The plan screen. [table] is the local draft while editing (else the shown table), [check] its row and column sums.
 * [saveMonths] are the months a save may apply from, the default first (BUD-004); [monthHasOwnTable] = the current
 * month has month-only changes that a save from the current month replaces. [saving] is the month picked in the
 * save dialog (null = closed).
 */
data class PlanUiState(
    val loaded: Boolean = false,
    val editable: Boolean = false,
    val version: YearMonth? = null,
    val month: YearMonth? = null,
    val table: BudgetTable = BudgetTable(),
    val check: TableCheck = BudgetMath.check(BudgetTable()),
    val changed: Boolean = false,
    val tab: PlanTab = PlanTab.CATEGORIES,
    val current: YearMonth? = null,
    val saveMonths: List<YearMonth> = emptyList(),
    val monthHasOwnTable: Boolean = false,
    val categoryForm: CategoryForm? = null,
    val incomeForm: IncomeForm? = null,
    val saving: YearMonth? = null,
    val showUnused: Boolean = false,
    val saved: Boolean = false,
    val error: UiError? = null,
) {
    /** Incomes by day, then table order (BUD-002). */
    val incomes: List<BudgetIncome> get() = PlanLogic.incomesByDay(table)
    val usedCategories: List<BudgetCategory> get() = table.categories.filterNot { it.unused }
    val unusedCategories: List<BudgetCategory> get() = table.categories.filter { it.unused }
}

/**
 * Builds and changes the plan as a local draft (BUD-001…BUD-004). The draft starts from the version that would apply
 * next month (else the one in force), so a change already saved "from next month" is what is edited again. Nothing is
 * written until «Сохранить»; saving with rows or columns that do not add up is allowed (BUD-003).
 */
class PlanViewModel(
    private val repository: BudgetRepository,
    private val writer: BudgetWriter,
    private val args: PlanArgs,
) : ViewModel() {
    private data class Local(
        val draft: BudgetTable? = null,
        val draftBase: BudgetTable? = null,
        val tab: PlanTab? = null,
        val categoryForm: CategoryForm? = null,
        val incomeForm: IncomeForm? = null,
        val saving: YearMonth? = null,
        val showUnused: Boolean = false,
        val saved: Boolean = false,
        val error: UiError? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PlanUiState> =
        combine(repository.observeInputs(), local) { inputs, local -> build(inputs, local) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PlanUiState())

    private fun build(
        inputs: BudgetInputs,
        local: Local,
    ): PlanUiState {
        val current = inputs.today.yearMonth
        val canManage = inputs.viewer?.let(BudgetRules::canManage) == true
        val base = baseTable(inputs, current)
        val table = local.draft ?: base
        val firstPlan = inputs.plans.isEmpty()
        val next = current.plusMonth()
        return PlanUiState(
            loaded = true,
            editable = canManage && args.version == null,
            version = args.version,
            month = args.month,
            table = table,
            check = BudgetMath.check(table),
            changed = local.draft != null && local.draft != local.draftBase,
            tab = local.tab ?: if (args.version != null) PlanTab.TABLE else PlanTab.CATEGORIES,
            current = current,
            saveMonths = if (firstPlan) listOf(current, next) else listOf(next, current),
            monthHasOwnTable = inputs.monthRows.any { it.month == current && it.table != null },
            categoryForm = local.categoryForm,
            incomeForm = local.incomeForm,
            saving = local.saving,
            showUnused = local.showUnused,
            saved = local.saved,
            error = local.error,
        )
    }

    /** The table the screen starts from: the shown version, the month's table, or the plan that applies next month. */
    private fun baseTable(
        inputs: BudgetInputs,
        current: YearMonth,
    ): BudgetTable {
        val version = args.version
        val month = args.month
        return when {
            version != null -> inputs.plans.firstOrNull { it.effectiveFrom == version }?.table ?: BudgetTable()
            month != null -> BudgetViews.month(inputs, month)?.table ?: BudgetTable()
            else -> BudgetViews.plan(inputs, current.plusMonth()).table
        }
    }

    fun selectTab(tab: PlanTab) = local.update { it.copy(tab = tab) }

    fun toggleUnused() = local.update { it.copy(showUnused = !it.showUnused) }

    // Categories (BUD-001, BUD-003)

    fun newCategory() {
        if (!state.value.editable) return
        local.update { it.copy(categoryForm = CategoryForm(id = null, deletable = true)) }
    }

    fun editCategory(id: String) {
        val state = state.value
        if (!state.editable) return
        val category = state.table.categories.firstOrNull { it.id == id } ?: return
        val cells =
            state.table.cells
                .filter { it.categoryId == id }
                .associate { it.incomeId to it.amount.toString() }
        local.update {
            it.copy(
                categoryForm =
                    CategoryForm(
                        id = id,
                        name = category.name,
                        amount =
                            category.amount
                                .takeIf { a -> a > 0 }
                                ?.toString()
                                .orEmpty(),
                        cells = cells,
                        unused = category.unused,
                    ),
            )
        }
        val before = state.saveMonths.firstOrNull() ?: return
        viewModelScope.launch {
            val used = repository.isCategoryUsed(id, before)
            local.update { l -> l.copy(categoryForm = l.categoryForm?.takeIf { it.id == id }?.copy(deletable = !used) ?: l.categoryForm) }
        }
    }

    fun onCategoryName(value: String) = updateCategory { it.copy(name = value.take(Validators.BUDGET_NAME_MAX), nameError = null) }

    fun onCategoryAmount(value: String) = updateCategory { it.copy(amount = BudgetFormat.input(value)) }

    fun onCell(
        incomeId: String,
        value: String,
    ) = updateCategory { it.copy(cells = it.cells + (incomeId to BudgetFormat.input(value))) }

    fun onCategoryUnused(unused: Boolean) = updateCategory { it.copy(unused = unused) }

    fun closeCategory() = local.update { it.copy(categoryForm = null) }

    /** «Готово» of the category sheet: a new category goes to the end; empty cells are no cells. */
    fun saveCategory() {
        val form = local.value.categoryForm ?: return
        val draft = draft()
        val name = PlanLogic.collapse(form.name)
        val taken = draft.categories.any { it.id != form.id && PlanLogic.collapse(it.name).equals(name, ignoreCase = true) }
        val nameError =
            when {
                name.isEmpty() -> FieldError.of(FieldErrorCode.REQUIRED)
                taken -> FieldError(Res.string.bud_plan_name_taken)
                else -> null
            }
        if (nameError != null) {
            local.update { it.copy(categoryForm = form.copy(nameError = nameError)) }
            return
        }
        val id = form.id ?: Ids.newId()
        val category = BudgetCategory(id, name, form.amountValue, form.unused)
        val categories =
            if (form.id == null) draft.categories + category else draft.categories.map { if (it.id == id) category else it }
        val incomeIds = draft.incomes.mapTo(HashSet()) { it.id }
        val cells =
            draft.cells.filterNot { it.categoryId == id } +
                form.cells.mapNotNull { (incomeId, text) ->
                    val amount = BudgetFormat.parse(text) ?: 0
                    if (amount > 0 && incomeId in incomeIds) BudgetCell(id, incomeId, amount) else null
                }
        edit(draft.copy(categories = categories, cells = cells)) { it.copy(categoryForm = null) }
    }

    /** «Удалить» in the category sheet; only for a category nothing uses (BUD-001). */
    fun deleteCategory() {
        val form = local.value.categoryForm ?: return
        val id = form.id
        if (id == null || form.deletable != true) {
            closeCategory()
            return
        }
        val draft = draft()
        edit(draft.copy(categories = draft.categories.filterNot { it.id == id }, cells = draft.cells.filterNot { it.categoryId == id })) {
            it.copy(categoryForm = null)
        }
    }

    /** «Выше» / «Ниже»: swaps the category with its neighbour of the same group (used or unused). */
    fun moveCategory(
        id: String,
        up: Boolean,
    ) {
        if (!state.value.editable) return
        val draft = draft()
        val moved = PlanLogic.move(draft.categories, id, up) ?: return
        edit(draft.copy(categories = moved))
    }

    // Incomes (BUD-002)

    /** A new income gets day 1 and amount 0 and opens its sheet. */
    fun newIncome() {
        if (!state.value.editable) return
        val draft = draft()
        val income = BudgetIncome(Ids.newId(), 1, 0)
        edit(draft.copy(incomes = draft.incomes + income)) {
            it.copy(incomeForm = IncomeForm(income.id, isNew = true, day = "1", amount = "", deletable = true))
        }
    }

    fun editIncome(id: String) {
        val state = state.value
        if (!state.editable) return
        val income = state.table.incomes.firstOrNull { it.id == id } ?: return
        val amount =
            income.amount
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty()
        local.update { it.copy(incomeForm = IncomeForm(id, isNew = false, day = income.day.toString(), amount = amount)) }
        val before = state.saveMonths.firstOrNull() ?: return
        viewModelScope.launch {
            val used = repository.isIncomeUsed(id, before)
            local.update { l -> l.copy(incomeForm = l.incomeForm?.takeIf { it.id == id }?.copy(deletable = !used) ?: l.incomeForm) }
        }
    }

    fun onIncomeDay(value: String) =
        local.update { l -> l.copy(incomeForm = l.incomeForm?.copy(day = value.filter { it in '0'..'9' }.take(2), dayError = null)) }

    fun onIncomeAmount(value: String) = local.update { l -> l.copy(incomeForm = l.incomeForm?.copy(amount = BudgetFormat.input(value))) }

    fun closeIncome() = local.update { it.copy(incomeForm = null) }

    fun saveIncome() {
        val form = local.value.incomeForm ?: return
        val day = form.day.toIntOrNull()
        if (day == null || day !in 1..31) {
            local.update { it.copy(incomeForm = form.copy(dayError = FieldError(Res.string.bud_plan_day_error))) }
            return
        }
        val draft = draft()
        val amount = BudgetFormat.parse(form.amount) ?: 0
        edit(draft.copy(incomes = draft.incomes.map { if (it.id == form.id) it.copy(day = day, amount = amount) else it })) {
            it.copy(incomeForm = null)
        }
    }

    /** «Удалить» in the income sheet: the income and its cells leave the draft. */
    fun deleteIncome() {
        val form = local.value.incomeForm ?: return
        if (form.deletable != true) return
        val draft = draft()
        edit(draft.copy(incomes = draft.incomes.filterNot { it.id == form.id }, cells = draft.cells.filterNot { it.incomeId == form.id })) {
            it.copy(incomeForm = null)
        }
    }

    // Saving (BUD-004)

    /** «Сохранить»: the plan asks from which month; month mode writes the month at once (M5-06). */
    fun openSave() {
        val state = state.value
        if (!state.editable) return
        val month = args.month
        if (month != null) {
            write { writer.editMonth(month, state.table) }
            return
        }
        local.update { it.copy(saving = state.saveMonths.firstOrNull()) }
    }

    fun chooseSaveMonth(month: YearMonth) = local.update { it.copy(saving = month) }

    fun dismissSave() = local.update { it.copy(saving = null) }

    fun confirmSave() {
        val from = local.value.saving ?: return
        val table = state.value.table
        local.update { it.copy(saving = null) }
        write { writer.savePlan(table, from) }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun draft(): BudgetTable = local.value.draft ?: state.value.table

    private fun edit(
        table: BudgetTable,
        also: (Local) -> Local = { it },
    ) {
        val base = state.value.table
        local.update { also(it.copy(draft = table, draftBase = it.draftBase ?: base)) }
    }

    private fun updateCategory(change: (CategoryForm) -> CategoryForm) =
        local.update { l -> l.copy(categoryForm = l.categoryForm?.let(change)) }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(saved = true, error = null) }
            } catch (e: LocalWriteException) {
                local.update { it.copy(error = UiError.Api(e.code)) }
            } catch (e: Exception) {
                local.update { it.copy(error = e.toUiError()) }
            }
        }
    }
}
