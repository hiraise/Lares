package app.lares.client.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.action_done
import app.lares.client.resources.ic_chevron_left
import app.lares.client.resources.ic_chevron_right
import app.lares.client.resources.ic_expand_more
import app.lares.client.resources.task_field_repeat
import app.lares.client.resources.task_repeat_custom
import app.lares.client.resources.task_repeat_daily
import app.lares.client.resources.task_repeat_how_often
import app.lares.client.resources.task_repeat_less
import app.lares.client.resources.task_repeat_mode_after
import app.lares.client.resources.task_repeat_mode_fixed
import app.lares.client.resources.task_repeat_monthly_preset
import app.lares.client.resources.task_repeat_more
import app.lares.client.resources.task_repeat_none
import app.lares.client.resources.task_repeat_on_days
import app.lares.client.resources.task_repeat_stop_hint
import app.lares.client.resources.task_repeat_weekly_preset
import app.lares.client.resources.task_repeat_yearly_preset
import app.lares.client.resources.task_unit_days
import app.lares.client.resources.task_unit_months
import app.lares.client.resources.task_unit_weeks
import app.lares.client.resources.task_unit_years
import app.lares.client.resources.weekdays
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SegmentedControl
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.validation.Validators
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The repeat rule (TSK-010, TSK-011): presets apply at once and close the sheet; "Custom…" shows the mode, the
 * interval with its unit and, for weekly schedules, the weekdays. Custom changes apply at once too; "Done" closes.
 */
@Composable
fun RepeatSheet(
    form: TaskForm,
    today: LocalDate,
    onPreset: (RepeatPreset) -> Unit,
    onRule: (Recurrence) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.task_field_repeat), onDismiss) {
        RepeatOptions(form, today, onPreset = {
            onPreset(it)
            onDismiss()
        }, onRule = onRule)
        if (form.preset() == RepeatPreset.CUSTOM || form.recurrence != null) {
            PrimaryButton(stringResource(Res.string.action_done), onDismiss)
        }
    }
}

/** The preset rows and, after "Custom…", the custom rule controls. */
@Composable
fun RepeatOptions(
    form: TaskForm,
    today: LocalDate,
    onPreset: (RepeatPreset) -> Unit,
    onRule: (Recurrence) -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf(form.preset() == RepeatPreset.CUSTOM) }
    val date = form.dueDate ?: today
    val selected = if (custom) RepeatPreset.CUSTOM else form.preset()
    ListGroup {
        RepeatPreset.entries.forEachIndexed { index, preset ->
            if (index > 0) ListDivider(inset = Spacing.l)
            val disabled = preset == RepeatPreset.NONE && !form.canStopRepeat
            ListRow(
                presetLabel(preset, date),
                modifier = Modifier.testTag("repeat_${preset.name.lowercase()}"),
                supporting = if (disabled) stringResource(Res.string.task_repeat_stop_hint) else null,
                enabled = !disabled,
                onClick = {
                    if (preset == RepeatPreset.CUSTOM) {
                        custom = true
                        // Weekly by default: the weekday toggles show what a custom rule can do.
                        if (form.recurrence == null) onRule(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK))
                    } else {
                        custom = false
                        onPreset(preset)
                    }
                },
                trailing = { RadioButton(selected = preset == selected, onClick = null) },
            )
        }
    }
    val rule = form.recurrence
    if (custom && rule != null) CustomRule(rule, date, onRule)
}

@Composable
private fun presetLabel(
    preset: RepeatPreset,
    date: LocalDate,
): String =
    when (preset) {
        RepeatPreset.NONE -> stringResource(Res.string.task_repeat_none)
        RepeatPreset.DAILY -> stringResource(Res.string.task_repeat_daily)
        RepeatPreset.WEEKLY -> stringArrayResource(Res.array.task_repeat_weekly_preset)[date.dayOfWeek.ordinal]
        RepeatPreset.MONTHLY -> stringResource(Res.string.task_repeat_monthly_preset, date.day.toString())
        RepeatPreset.YEARLY -> stringResource(Res.string.task_repeat_yearly_preset)
        RepeatPreset.CUSTOM -> stringResource(Res.string.task_repeat_custom)
    }

@Composable
private fun CustomRule(
    rule: Recurrence,
    date: LocalDate,
    onRule: (Recurrence) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l), modifier = Modifier.testTag("repeat_custom_controls")) {
        SegmentedControl(
            RecurrenceMode.entries,
            rule.mode,
            label = {
                stringResource(if (it == RecurrenceMode.FIXED) Res.string.task_repeat_mode_fixed else Res.string.task_repeat_mode_after)
            },
            onSelect = { onRule(rule.copy(mode = it)) },
        )
        Text(stringResource(Res.string.task_repeat_how_often), style = MaterialTheme.typography.labelLarge)
        IntervalStepper(rule, onRule)
        if (rule.isWeekly()) {
            Text(stringResource(Res.string.task_repeat_on_days), style = MaterialTheme.typography.labelLarge)
            WeekdayToggles(rule.weekdays) { onRule(rule.copy(weekdays = it)) }
        }
        Text(
            TasksLogic.rule(rule, date).text(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("repeat_summary"),
        )
    }
}

@Composable
private fun IntervalStepper(
    rule: Recurrence,
    onRule: (Recurrence) -> Unit,
) {
    val n = rule.interval
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        IconButton(onClick = { onRule(rule.copy(interval = n - 1)) }, enabled = n > 1) {
            AppIcon(Res.drawable.ic_chevron_left, stringResource(Res.string.task_repeat_less))
        }
        Text(
            n.toString(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).testTag("repeat_interval"),
        )
        IconButton(onClick = { onRule(rule.copy(interval = n + 1)) }, enabled = n < Validators.RECURRENCE_INTERVAL_MAX) {
            AppIcon(Res.drawable.ic_chevron_right, stringResource(Res.string.task_repeat_more))
        }
        Box {
            Row(
                Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .toggleable(menu, role = Role.DropdownList, onValueChange = { menu = it })
                    .testTag("repeat_unit"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    unitLabel(rule.unit, n),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                )
                AppIcon(Res.drawable.ic_expand_more, null, size = 18.dp)
                Box(Modifier.size(Spacing.s))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                RecurrenceUnit.entries.forEach { unit ->
                    DropdownMenuItem(
                        text = { Text(unitLabel(unit, n)) },
                        onClick = {
                            menu = false
                            onRule(rule.copy(unit = unit))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun unitLabel(
    unit: RecurrenceUnit,
    n: Int,
): String =
    pluralStringResource(
        when (unit) {
            RecurrenceUnit.DAY -> Res.plurals.task_unit_days
            RecurrenceUnit.WEEK -> Res.plurals.task_unit_weeks
            RecurrenceUnit.MONTH -> Res.plurals.task_unit_months
            RecurrenceUnit.YEAR -> Res.plurals.task_unit_years
        },
        n,
    )

/** Seven round toggles, Monday first; the last chosen day cannot be turned off. */
@Composable
private fun WeekdayToggles(
    selected: Set<DayOfWeek>,
    onChange: (Set<DayOfWeek>) -> Unit,
) {
    val short = stringArrayResource(Res.array.weekdays_short)
    val full = stringArrayResource(Res.array.weekdays)
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { day ->
            val on = day in selected
            Box(
                Modifier
                    .size(40.dp)
                    .then(
                        if (on) {
                            Modifier.background(
                                colors.primary,
                                CircleShape,
                            )
                        } else {
                            Modifier.border(1.dp, colors.outlineVariant, CircleShape)
                        },
                    ).toggleable(on, role = Role.Checkbox, onValueChange = {
                        val next = if (on) selected - day else selected + day
                        if (next.isNotEmpty()) onChange(next)
                    })
                    .semantics { contentDescription = full[day.ordinal] }
                    .testTag("weekday_${day.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    short[day.ordinal].take(2),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) colors.onPrimary else colors.onSurface,
                )
            }
        }
    }
}
