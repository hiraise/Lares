package app.lares.client.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.budget.FundingRow
import app.lares.client.resources.Res
import app.lares.client.resources.action_clear
import app.lares.client.resources.action_done
import app.lares.client.resources.bud_came_on
import app.lares.client.resources.bud_field_came
import app.lares.client.resources.bud_field_came_hint
import app.lares.client.resources.bud_field_date
import app.lares.client.resources.bud_fund_all
import app.lares.client.resources.bud_funded
import app.lares.client.resources.bud_income_gone
import app.lares.client.resources.bud_not_came
import app.lares.client.resources.bud_sheet_planned
import app.lares.client.resources.bud_sheet_title
import app.lares.client.resources.bud_to_fund
import app.lares.client.resources.ic_account_balance_wallet
import app.lares.client.resources.ic_today
import app.lares.client.resources.section_budget
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DateDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.FullScreenLoading
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun IncomeRoute(
    args: IncomeArgs,
    onBack: () -> Unit,
    viewModel: IncomeViewModel = koinViewModel(key = "income-${args.month}-${args.incomeId}") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    IncomePage(
        state,
        IncomeActions(
            onBack = onBack,
            onStartEdit = viewModel::startEdit,
            onAmount = viewModel::changeAmount,
            onCommit = viewModel::commit,
            onDate = viewModel::pickDate,
            onClear = viewModel::clear,
            onToggle = viewModel::toggle,
            onFundAll = viewModel::fundAll,
            onOpenSheet = viewModel::openSheet,
            onSheetText = viewModel::changeSheet,
            onSheetSave = viewModel::saveSheet,
            onSheetClose = viewModel::closeSheet,
        ),
    )
}

data class IncomeActions(
    val onBack: () -> Unit = {},
    val onStartEdit: () -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onCommit: () -> Unit = {},
    val onDate: (kotlinx.datetime.LocalDate) -> Unit = {},
    val onClear: () -> Unit = {},
    val onToggle: (FundingRow) -> Unit = {},
    val onFundAll: () -> Unit = {},
    val onOpenSheet: (FundingRow) -> Unit = {},
    val onSheetText: (String) -> Unit = {},
    val onSheetSave: () -> Unit = {},
    val onSheetClose: () -> Unit = {},
)

/**
 * The income page (BUD-011, BUD-012): «Пришло» and «Дата», the categories to transfer and the transferred ones,
 * «Отметить все», the free money of the income. Read-only members see the receipt as text and no checkboxes.
 */
@Composable
fun IncomePage(
    state: IncomeUiState,
    actions: IncomeActions,
) {
    val today = state.today
    if (!state.loaded || today == null) {
        FullScreenLoading()
        return
    }
    val view = state.view
    if (view == null) {
        AppPage(topBar = { DetailTopBar(stringResource(Res.string.section_budget), actions.onBack) }) {
            EmptyState(Res.drawable.ic_account_balance_wallet, stringResource(Res.string.bud_income_gone))
        }
        return
    }
    val summary = view.summary
    AppPage(topBar = { DetailTopBar(BudgetLogic.incomeTitle(summary, today).text(), actions.onBack) }) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            if (state.canManage) {
                ReceiptFields(state, actions)
            } else {
                val actual = summary.actual
                Text(
                    if (actual == null) {
                        stringResource(Res.string.bud_not_came)
                    } else {
                        stringResource(
                            Res.string.bud_came_on,
                            BudgetFormat.amount(actual),
                            summary.actualDate?.let { CalendarLogic.dayMonth(it, today).text() }.orEmpty(),
                        )
                    },
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    modifier = Modifier.testTag("income_receipt"),
                )
            }
            BudgetLogic.incomeShortfall(summary)?.let {
                Text(
                    it.text(),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("income_shortfall"),
                )
            }
            ErrorText(state.error)
        }
        val toFund = state.toFund
        val funded = state.funded
        if (toFund.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(Res.string.bud_to_fund))
                FundingGroup(toFund, state.canManage, actions, Modifier.testTag("income_to_fund"))
                if (state.canManage) {
                    TonalButton(stringResource(Res.string.bud_fund_all), actions.onFundAll, Modifier.padding(top = Spacing.xs))
                }
            }
        }
        if (funded.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(Res.string.bud_funded))
                FundingGroup(funded, state.canManage, actions, Modifier.testTag("income_funded"))
            }
        }
        BudgetLogic.incomeFree(summary)?.let {
            Text(
                it.text(),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color =
                    if ((summary.actual ?: 0) <
                        summary.transferred
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.padding(horizontal = Spacing.xs).testTag("income_free"),
            )
        }
    }
    state.sheet?.let { FundingAmountSheet(it, actions) }
}

/** «Пришло» (digits, prefilled on first edit) and «Дата» (a picker), «Очистить» when recorded. */
@Composable
private fun ReceiptFields(
    state: IncomeUiState,
    actions: IncomeActions,
) {
    val summary = state.view?.summary ?: return
    var picking by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        FormField(
            value = state.amountText,
            onValueChange = actions.onAmount,
            label = stringResource(Res.string.bud_field_came),
            hint = stringResource(Res.string.bud_field_came_hint, BudgetFormat.amount(summary.expected)),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            onDone = { focus.clearFocus() },
            modifier =
                Modifier
                    .onFocusChanged { if (it.hasFocus) actions.onStartEdit() else actions.onCommit() }
                    .testTag("income_amount"),
        )
        Box {
            FormField(
                value = state.date?.let { CalendarLogic.dayMonth(it, state.today ?: it).text() }.orEmpty(),
                onValueChange = {},
                label = stringResource(Res.string.bud_field_date),
                enabled = false,
                trailingIcon = { AppIcon(Res.drawable.ic_today, null) },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button) { picking = true }
                    .testTag("income_date"),
            )
        }
        if (summary.actual != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LinkButton(stringResource(Res.string.action_clear), actions.onClear, Modifier.testTag("income_clear"))
            }
        }
    }
    if (picking) {
        DateDialog(
            initial = state.date ?: summary.actualDate ?: state.today ?: return,
            canClear = false,
            onPick = { date -> date?.let(actions.onDate) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FundingGroup(
    rows: List<FundingRow>,
    canManage: Boolean,
    actions: IncomeActions,
    modifier: Modifier = Modifier,
) {
    ListGroup(modifier) {
        rows.forEachIndexed { index, row ->
            if (index > 0) ListDivider(if (canManage) 56.dp else Spacing.l)
            FundingRowItem(row, canManage, onToggle = { actions.onToggle(row) }, onAmount = { actions.onOpenSheet(row) })
        }
    }
}

/** «Перевели»: the amount transferred to one category; saved on «Готово», an empty field unticks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FundingAmountSheet(
    sheet: FundingSheet,
    actions: IncomeActions,
) {
    ModalBottomSheet(
        onDismissRequest = actions.onSheetClose,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .testTag("funding_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(sheet.name, style = MaterialTheme.typography.headlineSmall)
            FormField(
                value = sheet.text,
                onValueChange = actions.onSheetText,
                label = stringResource(Res.string.bud_sheet_title),
                hint = stringResource(Res.string.bud_sheet_planned, BudgetFormat.amount(sheet.planned)),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = actions.onSheetSave,
                modifier = Modifier.testTag("funding_sheet_amount"),
            )
            PrimaryButton(stringResource(Res.string.action_done), actions.onSheetSave, Modifier.testTag("funding_sheet_done"))
        }
    }
}
