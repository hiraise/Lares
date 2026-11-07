package app.lares.client.ui.budget.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.lares.client.data.budget.BudgetRepository
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.VersionRow
import app.lares.client.resources.Res
import app.lares.client.resources.bud_empty_title
import app.lares.client.resources.bud_plan_history
import app.lares.client.resources.bud_plan_in_force
import app.lares.client.resources.ic_history
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** «История плана» (BUD-004): versions newest first; dates in the household [zone]. */
data class PlanHistoryUiState(
    val loaded: Boolean = false,
    val rows: List<VersionRow> = emptyList(),
    val zone: TimeZone = TimeZone.UTC,
)

class PlanHistoryViewModel(
    repository: BudgetRepository,
) : ViewModel() {
    val state: StateFlow<PlanHistoryUiState> =
        repository
            .observeInputs()
            .map { PlanHistoryUiState(true, BudgetViews.history(it), it.zone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanHistoryUiState())
}

@Composable
fun PlanHistoryRoute(
    onBack: () -> Unit,
    onVersion: (YearMonth) -> Unit,
    viewModel: PlanHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlanHistoryPage(state, onBack, onVersion)
}

/** «С января 2027 · Ольга · 7 нояб.», «Действует» on the version in force; a tap opens it read-only. */
@Composable
fun PlanHistoryPage(
    state: PlanHistoryUiState,
    onBack: () -> Unit,
    onVersion: (YearMonth) -> Unit,
) {
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.bud_plan_history), onBack) }) {
        if (state.loaded && state.rows.isEmpty()) {
            EmptyState(Res.drawable.ic_history, stringResource(Res.string.bud_empty_title))
            return@AppPage
        }
        ListGroup(Modifier.testTag("plan_versions")) {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider(Spacing.l)
                ListRow(
                    PlanLogic.historyTitle(row).text(),
                    modifier = Modifier.testTag("plan_version_row"),
                    supporting = PlanLogic.historyLine(row, state.zone).text(),
                    trailingText = if (row.current) stringResource(Res.string.bud_plan_in_force) else null,
                    showChevron = true,
                    onClick = { onVersion(row.effectiveFrom) },
                )
            }
        }
    }
}
