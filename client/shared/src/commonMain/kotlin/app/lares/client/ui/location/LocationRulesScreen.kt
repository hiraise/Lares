package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_done
import app.lares.client.resources.action_save
import app.lares.client.resources.family_needs_internet
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_visibility
import app.lares.client.resources.loc_level_current_title
import app.lares.client.resources.loc_level_history_title
import app.lares.client.resources.rule_editor_level
import app.lares.client.resources.rule_editor_new
import app.lares.client.resources.rule_editor_subject
import app.lares.client.resources.rule_editor_target
import app.lares.client.resources.rule_editor_title
import app.lares.client.resources.rule_everyone
import app.lares.client.resources.rules_add
import app.lares.client.resources.rules_history_days
import app.lares.client.resources.rules_history_text
import app.lares.client.resources.rules_history_title
import app.lares.client.resources.rules_none
import app.lares.client.resources.rules_note
import app.lares.client.resources.rules_section
import app.lares.client.resources.rules_title
import app.lares.client.ui.common.text
import app.lares.client.ui.common.uiText
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.NoticeBanner
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.location.LocationLimits
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LocationRulesRoute(
    onBack: () -> Unit,
    viewModel: LocationRulesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LocationRulesScreen(
        state,
        LocationRulesActions(
            onBack = onBack,
            onRule = viewModel::openRule,
            onAdd = viewModel::openNew,
            onSave = viewModel::save,
            onHistoryDays = viewModel::setHistoryDays,
        ),
    )
    state.editor?.let { editor ->
        RuleEditorSheet(
            editor,
            state.names,
            onSubject = viewModel::onSubject,
            onTarget = viewModel::onTarget,
            onLevel = viewModel::onLevel,
            onDone = viewModel::applyEditor,
            onDelete = { viewModel.deleteRule(editor.draft.key) },
            onDismiss = viewModel::closeEditor,
        )
    }
}

class LocationRulesActions(
    val onBack: () -> Unit,
    val onRule: (Int) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onHistoryDays: (Int) -> Unit = {},
)

/** «Геопозиция: кто кого видит» (plan M4-10): the rules as sentences, «Добавить правило», «Сохранить», the history period. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationRulesScreen(
    state: LocationRulesUiState,
    actions: LocationRulesActions,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.rules_title), actions.onBack) }) {
        if (!state.online ||
            state.loadFailed
        ) {
            NoticeBanner(stringResource(Res.string.family_needs_internet), Modifier.testTag("rules_offline"))
        }
        Text(stringResource(Res.string.rules_note), style = MaterialTheme.typography.bodyMedium, color = muted)
        if (state.loaded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(Res.string.rules_section))
                ListGroup(Modifier.testTag("rules_list")) {
                    if (state.rules.isEmpty()) ListRow(stringResource(Res.string.rules_none))
                    state.rules.forEachIndexed { index, rule ->
                        if (index > 0) ListDivider()
                        val error = state.rowErrors[rule.key]
                        ListRow(
                            LocationRulesLogic.sentence(rule, state.names).text(),
                            Modifier.testTag("rule_${rule.key}"),
                            icon = Res.drawable.ic_visibility,
                            supporting = error?.text(),
                            titleColor = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            showChevron = state.editable,
                            enabled = state.editable,
                            onClick = if (state.editable) ({ actions.onRule(rule.key) }) else null,
                        )
                    }
                }
            }
            if (state.editable) {
                TonalButton(
                    stringResource(Res.string.rules_add),
                    actions.onAdd,
                    Modifier.fillMaxWidth().testTag("rules_add"),
                    enabled = state.canAdd,
                    icon = Res.drawable.ic_add,
                )
                PrimaryButton(
                    stringResource(Res.string.action_save),
                    actions.onSave,
                    Modifier.testTag("rules_save"),
                    enabled = state.dirty,
                    loading = state.saving,
                )
            }
            ErrorText(state.error)
        }
        state.historyDays?.let { days ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(Res.string.rules_history_title))
                if (state.historyEditable) {
                    FlowRow(
                        Modifier.testTag("history_days"),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        LocationLimits.HISTORY_DAYS_CHOICES.forEach { choice ->
                            AppFilterChip(
                                choice == days,
                                pluralStringResource(Res.plurals.rules_history_days, choice, choice),
                                { actions.onHistoryDays(choice) },
                                Modifier.testTag("history_days_$choice"),
                            )
                        }
                    }
                } else {
                    // Offline or not an administrator: the current period only.
                    Text(
                        pluralStringResource(Res.plurals.rules_history_days, days, days),
                        Modifier.testTag("history_days_value"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(stringResource(Res.string.rules_history_text), style = MaterialTheme.typography.bodySmall, color = muted)
                ErrorText(state.historyError)
            }
        }
    }
}

/** One rule: «Кто», «Кого», «Что видно»; «Готово» puts it into the list, «Удалить» removes it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorSheet(
    editor: RuleEditor,
    names: RuleNames,
    onSubject: (RuleParty) -> Unit,
    onTarget: (RuleParty) -> Unit,
    onLevel: (VisibilityLevel) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = names.options(uiText(Res.string.rule_everyone))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .testTag("rule_editor"),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(
                stringResource(if (editor.isNew) Res.string.rule_editor_new else Res.string.rule_editor_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                LocationRulesLogic.sentence(editor.draft, names).text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rule_sentence"),
            )
            PartyChoice(stringResource(Res.string.rule_editor_subject), options, editor.draft.subject, "subject", onSubject)
            PartyChoice(stringResource(Res.string.rule_editor_target), options, editor.draft.target, "target", onTarget)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(Res.string.rule_editor_level), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AppFilterChip(
                        editor.draft.level == VisibilityLevel.CURRENT,
                        stringResource(Res.string.loc_level_current_title),
                        { onLevel(VisibilityLevel.CURRENT) },
                        Modifier.testTag("level_current"),
                    )
                    AppFilterChip(
                        editor.draft.level == VisibilityLevel.HISTORY,
                        stringResource(Res.string.loc_level_history_title),
                        { onLevel(VisibilityLevel.HISTORY) },
                        Modifier.testTag("level_history"),
                    )
                }
            }
            PrimaryButton(stringResource(Res.string.action_done), onDone, Modifier.testTag("rule_done"))
            if (!editor.isNew) {
                TonalButton(stringResource(Res.string.action_delete), onDelete, Modifier.fillMaxWidth().testTag("rule_delete"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartyChoice(
    title: String,
    options: List<PartyOption>,
    selected: RuleParty,
    tag: String,
    onSelect: (RuleParty) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            options.forEachIndexed { index, option ->
                AppFilterChip(
                    option.party == selected,
                    option.name.text(),
                    { onSelect(option.party) },
                    Modifier.testTag("${tag}_$index"),
                )
            }
        }
    }
}
