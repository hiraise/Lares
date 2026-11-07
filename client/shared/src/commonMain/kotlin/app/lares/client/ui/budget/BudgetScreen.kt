package app.lares.client.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_back
import app.lares.client.resources.bud_categories
import app.lares.client.resources.bud_create_plan
import app.lares.client.resources.bud_empty_hint_manage
import app.lares.client.resources.bud_empty_hint_view
import app.lares.client.resources.bud_empty_title
import app.lares.client.resources.bud_incomes
import app.lares.client.resources.bud_months_all
import app.lares.client.resources.bud_next_month
import app.lares.client.resources.bud_prev_month
import app.lares.client.resources.ic_account_balance_wallet
import app.lares.client.resources.ic_arrow_back
import app.lares.client.resources.ic_chevron_left
import app.lares.client.resources.ic_chevron_right
import app.lares.client.resources.section_budget
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.FullScreenLoading
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * «Ещё › Бюджет» (plan M5-04). The month shown is kept in saved state, so it survives process death; [initialMonth]
 * comes from «Все месяцы». [onCreatePlan] is null until the plan editor exists (M5-05).
 */
@Composable
fun BudgetRoute(
    initialMonth: YearMonth?,
    onBack: () -> Unit,
    onIncome: (YearMonth, String) -> Unit,
    onMonths: () -> Unit,
    onCreatePlan: (() -> Unit)? = null,
    viewModel: BudgetViewModel = koinViewModel(),
) {
    var saved by rememberSaveable { mutableStateOf(initialMonth?.toString()) }
    LaunchedEffect(saved) { viewModel.show(saved?.let(YearMonth::parse)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BudgetScreen(
        state,
        BudgetActions(
            onBack = onBack,
            onPrevious = { state.previous?.let { saved = it.toString() } },
            onNext = { state.next?.let { saved = it.toString() } },
            onMonths = onMonths,
            onIncome = { id -> state.month?.let { onIncome(it, id) } },
            onCreatePlan = onCreatePlan,
        ),
    )
}

data class BudgetActions(
    val onBack: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onMonths: () -> Unit = {},
    val onIncome: (String) -> Unit = {},
    val onCreatePlan: (() -> Unit)? = null,
)

/** The month: hero, «Поступления», «Категории»; the empty state without a plan. */
@Composable
fun BudgetScreen(
    state: BudgetUiState,
    actions: BudgetActions,
) {
    val month = state.month
    val today = state.today
    if (!state.loaded || month == null || today == null) {
        FullScreenLoading()
        return
    }
    val view = state.view
    if (view == null) {
        AppPage(topBar = { DetailTopBar(stringResource(Res.string.section_budget), actions.onBack) }) {
            EmptyState(
                Res.drawable.ic_account_balance_wallet,
                stringResource(if (state.canManage) Res.string.bud_empty_hint_manage else Res.string.bud_empty_hint_view),
                title = stringResource(Res.string.bud_empty_title),
                modifier = Modifier.testTag("budget_empty"),
            )
            val create = actions.onCreatePlan
            if (state.canManage && create != null) PrimaryButton(stringResource(Res.string.bud_create_plan), create)
        }
        return
    }
    AppPage(topBar = { MonthTopBar(state, actions) }) {
        BudgetHero(month, view.summary)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.bud_incomes))
            ListGroup(Modifier.testTag("budget_incomes")) {
                view.summary.incomes.forEachIndexed { index, income ->
                    if (index > 0) ListDivider(Spacing.l)
                    val id = income.incomeId
                    IncomeRow(income, today, onClick = id?.let { { actions.onIncome(it) } })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.bud_categories))
            ListGroup(Modifier.testTag("budget_categories")) {
                view.summary.categories.forEachIndexed { index, category ->
                    if (index > 0) ListDivider(Spacing.l)
                    CategoryRow(category, BudgetLogic.categoryChanged(category.categoryId, view.differences))
                }
            }
        }
    }
}

/** Back, ‹, the month title (a tap opens «Все месяцы»), ›. */
@Composable
private fun MonthTopBar(
    state: BudgetUiState,
    actions: BudgetActions,
) {
    val month = state.month ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) { AppIcon(Res.drawable.ic_arrow_back, stringResource(Res.string.action_back)) }
        Text(
            BudgetLogic.monthTitle(month).text(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = actions.onMonths)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.s)
                    .semantics { heading() }
                    .testTag("budget_month_title"),
        )
        IconButton(onClick = actions.onPrevious, enabled = state.previous != null) {
            AppIcon(Res.drawable.ic_chevron_left, stringResource(Res.string.bud_prev_month))
        }
        IconButton(onClick = actions.onNext, enabled = state.next != null) {
            AppIcon(Res.drawable.ic_chevron_right, stringResource(Res.string.bud_next_month))
        }
    }
}

/** «Все месяцы» (BUD-020): newest first, each with expected, actual and its free money or shortfall. */
@Composable
fun MonthsRoute(
    onBack: () -> Unit,
    onPick: (YearMonth) -> Unit,
    viewModel: BudgetViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MonthsPage(state, onBack, onPick)
}

@Composable
fun MonthsPage(
    state: BudgetUiState,
    onBack: () -> Unit,
    onPick: (YearMonth) -> Unit,
) {
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.bud_months_all), onBack) }) {
        if (state.loaded && state.months.isEmpty()) {
            EmptyState(Res.drawable.ic_account_balance_wallet, stringResource(Res.string.bud_empty_title))
            return@AppPage
        }
        ListGroup(Modifier.testTag("budget_months")) {
            state.months.forEachIndexed { index, row ->
                if (index > 0) ListDivider(Spacing.l)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(row.month) }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = Spacing.l, vertical = 14.dp)
                        .testTag("budget_month_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(BudgetLogic.monthTitle(row.month).text(), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            BudgetLogic.monthLine(row.expected, row.actual, row.current).text(),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BudgetLogic.monthResult(row.free, row.shortfall)?.let {
                        Text(
                            it.text(),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = if (row.free > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
