package app.lares.client.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.lares.client.resources.Res
import app.lares.client.resources.action_done
import app.lares.client.resources.bud_extra_amount
import app.lares.client.resources.bud_extra_income
import app.lares.client.resources.bud_extra_label_hint
import app.lares.client.resources.bud_field_date
import app.lares.client.resources.bud_plan_name
import app.lares.client.resources.ic_today
import app.lares.client.ui.budget.plan.PlanSheet
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.DateDialog
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.PrimaryButton
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

data class ExtraIncomeSheetActions(
    val onLabel: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onDate: (LocalDate) -> Unit = {},
    val onDone: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** «Добавить поступление» (BUD-011): «Название» (optional), «Сумма» (required), «Дата» (default today). */
@Composable
fun ExtraIncomeSheet(
    form: ExtraIncomeForm,
    today: LocalDate,
    actions: ExtraIncomeSheetActions,
) {
    var picking by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }
    PlanSheet(actions.onDismiss, "extra_sheet") {
        Text(stringResource(Res.string.bud_extra_income), style = MaterialTheme.typography.headlineSmall)
        FormField(
            form.amount,
            actions.onAmount,
            stringResource(Res.string.bud_extra_amount),
            modifier = Modifier.testTag("extra_amount"),
            error = form.amountError,
            keyboardType = KeyboardType.Number,
            focusRequester = amountFocus,
        )
        FormField(
            form.label,
            actions.onLabel,
            stringResource(Res.string.bud_plan_name),
            modifier = Modifier.testTag("extra_label"),
            hint = stringResource(Res.string.bud_extra_label_hint),
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
            onDone = actions.onDone,
        )
        Box {
            FormField(
                value = CalendarLogic.dayMonth(form.date, today).text(),
                onValueChange = {},
                label = stringResource(Res.string.bud_field_date),
                enabled = false,
                trailingIcon = { AppIcon(Res.drawable.ic_today, null) },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button) { picking = true }
                    .testTag("extra_date"),
            )
        }
        PrimaryButton(stringResource(Res.string.action_done), actions.onDone, modifier = Modifier.testTag("extra_done"))
    }
    if (picking) {
        DateDialog(
            initial = form.date,
            canClear = false,
            onPick = { date -> date?.let(actions.onDate) },
            onDismiss = { picking = false },
        )
    }
}
