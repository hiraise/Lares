package app.lares.client.ui.budget.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_save
import app.lares.client.resources.bud_categories
import app.lares.client.resources.bud_incomes
import app.lares.client.resources.bud_plan
import app.lares.client.resources.bud_plan_add_category
import app.lares.client.resources.bud_plan_add_income
import app.lares.client.resources.bud_plan_bad_cells
import app.lares.client.resources.bud_plan_category_actions
import app.lares.client.resources.bud_plan_discard_text
import app.lares.client.resources.bud_plan_empty_categories
import app.lares.client.resources.bud_plan_empty_incomes
import app.lares.client.resources.bud_plan_history
import app.lares.client.resources.bud_plan_move_down
import app.lares.client.resources.bud_plan_move_up
import app.lares.client.resources.bud_plan_save_title
import app.lares.client.resources.bud_plan_save_warning
import app.lares.client.resources.bud_plan_tab_table
import app.lares.client.resources.bud_plan_unused_group
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_error
import app.lares.client.resources.ic_expand_less
import app.lares.client.resources.ic_expand_more
import app.lares.client.resources.ic_history
import app.lares.client.resources.ic_more_vert
import app.lares.client.resources.task_discard_confirm
import app.lares.client.resources.task_discard_keep
import app.lares.client.resources.task_discard_title
import app.lares.client.ui.budget.BudgetFormat
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FullScreenLoading
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SegmentedControl
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.contract.entities.BudgetCategory
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * «Ещё › Бюджет › План» (plan M5-05): the plan editor, or a version read-only ([PlanArgs.version]). Leaving with
 * unsaved changes asks «Отменить изменения?»; a save goes back.
 */
@Composable
fun PlanRoute(
    args: PlanArgs,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    viewModel: PlanViewModel = koinViewModel(key = "budget-plan-$args") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.changed && !state.saved,
        onBackCompleted = { confirmDiscard = true },
    )
    PlanScreen(
        state,
        PlanActions(
            onBack = { if (state.changed) confirmDiscard = true else onBack() },
            onHistory = onHistory,
            onTab = viewModel::selectTab,
            onSave = viewModel::openSave,
            onToggleUnused = viewModel::toggleUnused,
            onNewCategory = viewModel::newCategory,
            onCategory = viewModel::editCategory,
            onMove = viewModel::moveCategory,
            onNewIncome = viewModel::newIncome,
            onIncome = viewModel::editIncome,
        ),
    )
    state.categoryForm?.let { form ->
        CategorySheet(
            form,
            state.incomes,
            CategorySheetActions(
                onName = viewModel::onCategoryName,
                onAmount = viewModel::onCategoryAmount,
                onCell = viewModel::onCell,
                onUnused = viewModel::onCategoryUnused,
                onDone = viewModel::saveCategory,
                onDelete = viewModel::deleteCategory,
                onDismiss = viewModel::closeCategory,
            ),
        )
    }
    state.incomeForm?.let { form ->
        IncomeSheet(
            form,
            IncomeSheetActions(
                onDay = viewModel::onIncomeDay,
                onAmount = viewModel::onIncomeAmount,
                onDone = viewModel::saveIncome,
                onDelete = viewModel::deleteIncome,
                onDismiss = viewModel::closeIncome,
            ),
        )
    }
    state.saving?.let { month ->
        SaveDialog(state, month, viewModel::chooseSaveMonth, viewModel::confirmSave, viewModel::dismissSave)
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.task_discard_title),
            text = stringResource(Res.string.bud_plan_discard_text),
            confirmLabel = stringResource(Res.string.task_discard_confirm),
            dismissLabel = stringResource(Res.string.task_discard_keep),
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
}

data class PlanActions(
    val onBack: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onTab: (PlanTab) -> Unit = {},
    val onSave: () -> Unit = {},
    val onToggleUnused: () -> Unit = {},
    val onNewCategory: () -> Unit = {},
    val onCategory: (String) -> Unit = {},
    val onMove: (String, Boolean) -> Unit = { _, _ -> },
    val onNewIncome: () -> Unit = {},
    val onIncome: (String) -> Unit = {},
)

/** The summary bar, «Категории | Поступления | Таблица» and the selected tab. */
@Composable
fun PlanScreen(
    state: PlanUiState,
    actions: PlanActions,
) {
    if (!state.loaded) {
        FullScreenLoading()
        return
    }
    val version = state.version
    val title = if (version != null) PlanLogic.versionTitle(version).text() else stringResource(Res.string.bud_plan)
    AppPage(
        topBar = {
            DetailTopBar(title, actions.onBack) {
                if (version == null && state.month == null) {
                    IconButton(onClick = actions.onHistory, modifier = Modifier.testTag("plan_history")) {
                        AppIcon(Res.drawable.ic_history, stringResource(Res.string.bud_plan_history))
                    }
                }
                if (state.editable) {
                    LinkButton(
                        stringResource(Res.string.action_save),
                        actions.onSave,
                        enabled = state.changed,
                        modifier = Modifier.testTag("plan_save"),
                    )
                }
            }
        },
    ) {
        SummaryBar(state)
        SegmentedControl(
            PlanTab.entries,
            state.tab,
            label = {
                stringResource(
                    when (it) {
                        PlanTab.CATEGORIES -> Res.string.bud_categories
                        PlanTab.INCOMES -> Res.string.bud_incomes
                        PlanTab.TABLE -> Res.string.bud_plan_tab_table
                    },
                )
            },
            onSelect = actions.onTab,
        )
        ErrorText(state.error)
        when (state.tab) {
            PlanTab.CATEGORIES -> CategoriesTab(state, actions)
            PlanTab.INCOMES -> IncomesTab(state, actions)
            PlanTab.TABLE -> PlanTable(state.table, state.check)
        }
    }
}

/** «Доход 346 000 · Категории 346 000 ✓», or the difference and «Есть строки или столбцы…» in the warning color. */
@Composable
private fun SummaryBar(state: PlanUiState) {
    val warning = LaresTheme.colors.warning
    val check = state.check
    val balanced = PlanLogic.balanced(check)
    ListGroup(Modifier.testTag("plan_summary")) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    PlanLogic.summary(check).text(),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    modifier = Modifier.weight(1f, fill = false).testTag("plan_summary_text"),
                )
                if (balanced) AppIcon(Res.drawable.ic_check, null, tint = LaresTheme.colors.success)
            }
            PlanLogic.mismatch(check)?.let {
                Text(
                    it.text(),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = warning,
                    modifier = Modifier.testTag("plan_mismatch"),
                )
            }
            if (check.badRows.isNotEmpty() || check.badColumns.isNotEmpty()) {
                Text(
                    stringResource(Res.string.bud_plan_bad_cells),
                    style = MaterialTheme.typography.bodySmall,
                    color = warning,
                    modifier = Modifier.testTag("plan_bad_cells"),
                )
            }
        }
    }
}

@Composable
private fun CategoriesTab(
    state: PlanUiState,
    actions: PlanActions,
) {
    val used = state.usedCategories
    val unused = state.unusedCategories
    if (used.isEmpty() && unused.isEmpty()) EmptyLine(stringResource(Res.string.bud_plan_empty_categories))
    if (used.isNotEmpty()) {
        ListGroup(Modifier.testTag("plan_categories")) {
            used.forEachIndexed { index, category ->
                if (index > 0) ListDivider(Spacing.l)
                CategoryPlanRow(category, state, actions)
            }
        }
    }
    if (unused.isNotEmpty()) {
        ListGroup(Modifier.testTag("plan_unused")) {
            ListRow(
                stringResource(Res.string.bud_plan_unused_group),
                trailingText = unused.size.toString(),
                onClick = actions.onToggleUnused,
                trailing = {
                    AppIcon(
                        if (state.showUnused) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (state.showUnused) {
                unused.forEach { category ->
                    ListDivider(Spacing.l)
                    CategoryPlanRow(category, state, actions)
                }
            }
        }
    }
    if (state.editable) {
        TonalButton(
            stringResource(Res.string.bud_plan_add_category),
            actions.onNewCategory,
            icon = Res.drawable.ic_add,
            modifier = Modifier.testTag("plan_add_category"),
        )
    }
}

/** Name, monthly amount, a warning icon and «Распределено …» for a row that does not add up; «Выше» / «Ниже». */
@Composable
private fun CategoryPlanRow(
    category: BudgetCategory,
    state: PlanUiState,
    actions: PlanActions,
) {
    val bad = category.id in state.check.badRows
    val warning = LaresTheme.colors.warning
    val sum = state.check.rows[category.id] ?: 0
    ListRow(
        category.name,
        modifier = Modifier.testTag("plan_category_row"),
        titleMaxLines = 2,
        supporting = if (bad) PlanLogic.distributed(sum, category.amount).text() else null,
        trailingText = BudgetFormat.amount(category.amount),
        onClick = if (state.editable) ({ actions.onCategory(category.id) }) else null,
        trailing = {
            if (bad) AppIcon(Res.drawable.ic_error, null, tint = warning, modifier = Modifier.testTag("plan_row_warning"))
            if (state.editable) MoveMenu(category, state, actions)
        },
    )
}

@Composable
private fun MoveMenu(
    category: BudgetCategory,
    state: PlanUiState,
    actions: PlanActions,
) {
    var open by remember { mutableStateOf(false) }
    val categories = state.table.categories
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("plan_category_menu")) {
            AppIcon(Res.drawable.ic_more_vert, stringResource(Res.string.bud_plan_category_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(true to Res.string.bud_plan_move_up, false to Res.string.bud_plan_move_down).forEach { (up, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { AppIcon(if (up) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more, null) },
                    enabled = PlanLogic.canMove(categories, category.id, up),
                    onClick = {
                        open = false
                        actions.onMove(category.id, up)
                    },
                )
            }
        }
    }
}

@Composable
private fun IncomesTab(
    state: PlanUiState,
    actions: PlanActions,
) {
    val incomes = state.incomes
    val warning = LaresTheme.colors.warning
    if (incomes.isEmpty()) {
        EmptyLine(stringResource(Res.string.bud_plan_empty_incomes))
    } else {
        ListGroup(Modifier.testTag("plan_incomes")) {
            incomes.forEachIndexed { index, income ->
                if (index > 0) ListDivider(Spacing.l)
                val bad = income.id in state.check.badColumns
                val sum = state.check.columns[income.id] ?: 0
                ListRow(
                    PlanLogic.incomeRow(income).text(),
                    modifier = Modifier.testTag("plan_income_row"),
                    supporting = PlanLogic.distributed(sum, income.amount).text(),
                    onClick = if (state.editable) ({ actions.onIncome(income.id) }) else null,
                    trailing = { if (bad) AppIcon(Res.drawable.ic_error, null, tint = warning) },
                )
            }
        }
    }
    if (state.editable) {
        TonalButton(
            stringResource(Res.string.bud_plan_add_income),
            actions.onNewIncome,
            icon = Res.drawable.ic_add,
            modifier = Modifier.testTag("plan_add_income"),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s),
    )
}

/**
 * «С какого месяца действуют изменения?»: the next month (default; the current one for the very first plan) or the
 * current month, with the warning when the current month has its own changes (BUD-004).
 */
@Composable
private fun SaveDialog(
    state: PlanUiState,
    selected: YearMonth,
    onSelect: (YearMonth) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = state.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.bud_plan_save_title), style = MaterialTheme.typography.headlineSmall) },
        confirmButton = {
            LinkButton(stringResource(Res.string.action_save), onConfirm, modifier = Modifier.testTag("plan_save_confirm"))
        },
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        text = {
            Column {
                state.saveMonths.forEach { month ->
                    ChoiceRow(PlanLogic.saveOption(month, current).text(), month == selected, Modifier.testTag("plan_save_option")) {
                        onSelect(month)
                    }
                }
                if (selected == current && current != null && state.monthHasOwnTable) {
                    Text(
                        stringResource(Res.string.bud_plan_save_warning, PlanLogic.genitive(current).text()),
                        style = MaterialTheme.typography.bodySmall,
                        color = LaresTheme.colors.warning,
                        modifier = Modifier.padding(top = Spacing.s).testTag("plan_save_warning"),
                    )
                }
            }
        },
    )
}
