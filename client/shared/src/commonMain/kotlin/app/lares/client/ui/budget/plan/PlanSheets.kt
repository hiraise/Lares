package app.lares.client.ui.budget.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_done
import app.lares.client.resources.bud_plan_amount
import app.lares.client.resources.bud_plan_category
import app.lares.client.resources.bud_plan_category_new
import app.lares.client.resources.bud_plan_day
import app.lares.client.resources.bud_plan_day_hint
import app.lares.client.resources.bud_plan_expected
import app.lares.client.resources.bud_plan_income
import app.lares.client.resources.bud_plan_income_new
import app.lares.client.resources.bud_plan_name
import app.lares.client.resources.bud_plan_no_incomes
import app.lares.client.resources.bud_plan_per_income
import app.lares.client.resources.bud_plan_unused
import app.lares.client.resources.bud_plan_unused_hint
import app.lares.client.ui.common.text
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.contract.entities.BudgetIncome
import org.jetbrains.compose.resources.stringResource

data class CategorySheetActions(
    val onName: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onCell: (String, String) -> Unit = { _, _ -> },
    val onUnused: (Boolean) -> Unit = {},
    val onDone: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The category sheet (BUD-001, BUD-003): name, monthly amount, one field per income by day («5-е · из 162 000»),
 * «Распределено 58 000 из 60 000» live (warning color when it differs), «Не использовать», «Удалить» when nothing uses
 * the category.
 */
@Composable
fun CategorySheet(
    form: CategoryForm,
    incomes: List<BudgetIncome>,
    actions: CategorySheetActions,
) {
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (form.id == null) nameFocus.requestFocus() }
    PlanSheet(actions.onDismiss, "category_sheet") {
        Text(
            stringResource(if (form.id == null) Res.string.bud_plan_category_new else Res.string.bud_plan_category),
            style = MaterialTheme.typography.headlineSmall,
        )
        FormField(
            form.name,
            actions.onName,
            stringResource(Res.string.bud_plan_name),
            modifier = Modifier.testTag("category_name"),
            error = form.nameError,
            capitalization = KeyboardCapitalization.Sentences,
            focusRequester = nameFocus,
        )
        FormField(
            form.amount,
            actions.onAmount,
            stringResource(Res.string.bud_plan_amount),
            modifier = Modifier.testTag("category_amount"),
            keyboardType = KeyboardType.Number,
        )
        Text(stringResource(Res.string.bud_plan_per_income), style = MaterialTheme.typography.titleSmall)
        if (incomes.isEmpty()) {
            Text(
                stringResource(Res.string.bud_plan_no_incomes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        incomes.forEachIndexed { index, income ->
            FormField(
                form.cells[income.id].orEmpty(),
                { actions.onCell(income.id, it) },
                PlanLogic.cellLabel(income).text(),
                modifier = Modifier.testTag("category_cell"),
                keyboardType = KeyboardType.Number,
                imeAction = if (index == incomes.lastIndex) ImeAction.Done else ImeAction.Next,
                onDone = actions.onDone,
            )
        }
        if (incomes.isNotEmpty()) {
            val differs = form.distributed != form.amountValue
            Text(
                PlanLogic.distributed(form.distributed, form.amountValue).text(),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = if (differs) LaresTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("category_distributed"),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(form.unused, role = Role.Switch, onValueChange = actions.onUnused)
                .testTag("category_unused"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.bud_plan_unused), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(Res.string.bud_plan_unused_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = form.unused, onCheckedChange = null)
        }
        PrimaryButton(stringResource(Res.string.action_done), actions.onDone, modifier = Modifier.testTag("category_done"))
        if (form.id != null && form.deletable == true) {
            LinkButton(
                stringResource(Res.string.action_delete),
                actions.onDelete,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("category_delete"),
            )
        }
    }
}

data class IncomeSheetActions(
    val onDay: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onDone: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** The income sheet (BUD-002): day of the month and expected amount; «Удалить» only when nothing uses it. */
@Composable
fun IncomeSheet(
    form: IncomeForm,
    actions: IncomeSheetActions,
) {
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (form.isNew) amountFocus.requestFocus() }
    PlanSheet(actions.onDismiss, "income_sheet") {
        Text(
            stringResource(if (form.isNew) Res.string.bud_plan_income_new else Res.string.bud_plan_income),
            style = MaterialTheme.typography.headlineSmall,
        )
        FormField(
            form.day,
            actions.onDay,
            stringResource(Res.string.bud_plan_day),
            modifier = Modifier.testTag("income_day"),
            hint = stringResource(Res.string.bud_plan_day_hint),
            error = form.dayError,
            keyboardType = KeyboardType.Number,
        )
        FormField(
            form.amount,
            actions.onAmount,
            stringResource(Res.string.bud_plan_expected),
            modifier = Modifier.testTag("income_expected"),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            onDone = actions.onDone,
            focusRequester = amountFocus,
        )
        PrimaryButton(stringResource(Res.string.action_done), actions.onDone, modifier = Modifier.testTag("income_done"))
        if (form.deletable == true) {
            LinkButton(
                stringResource(Res.string.action_delete),
                actions.onDelete,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("income_delete"),
            )
        }
    }
}

/** A bottom sheet like the tag sheet: fully expanded (moves up with the keyboard), its content scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(
    onDismiss: () -> Unit,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .imePadding()
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
            content = content,
        )
    }
}

/** A radio row of the save dialog. */
@Composable
internal fun ChoiceRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
