package app.lares.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_save
import app.lares.client.resources.months
import app.lares.client.resources.relative_birthday_day
import app.lares.client.resources.relative_birthday_month
import app.lares.client.resources.relative_birthday_year
import app.lares.client.ui.theme.Spacing
import app.lares.contract.validation.Validators
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Day, month and an optional year as three scrolling columns (plan M0-08: pickers, not free text); used for birthdays
 * and memorable dates. The year column starts with [unknownYear] (null); [clearLabel] adds a button that saves all null.
 */
@Composable
fun DayMonthYearDialog(
    title: String,
    unknownYear: String,
    day: Int?,
    month: Int?,
    year: Int?,
    onSave: (Int?, Int?, Int?) -> Unit,
    onDismiss: () -> Unit,
    clearLabel: String? = null,
) {
    var selectedDay by remember { mutableStateOf(day) }
    var selectedMonth by remember { mutableStateOf(month) }
    var selectedYear by remember { mutableStateOf(year) }
    val currentYear =
        remember {
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .year
        }
    val months = stringArrayResource(Res.array.months)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PickerColumn(
                    stringResource(Res.string.relative_birthday_day),
                    (1..31).map { it to it.toString() },
                    selectedDay,
                    { selectedDay = it },
                    Modifier.weight(0.8f).testTag("picker_day"),
                )
                PickerColumn(
                    stringResource(Res.string.relative_birthday_month),
                    months.mapIndexed { index, name -> (index + 1) to name },
                    selectedMonth,
                    { selectedMonth = it },
                    Modifier.weight(1.4f).testTag("picker_month"),
                )
                PickerColumn(
                    stringResource(Res.string.relative_birthday_year),
                    listOf<Pair<Int?, String>>(
                        null to unknownYear,
                    ) + (currentYear downTo Validators.MIN_BIRTH_YEAR).map { it to it.toString() },
                    selectedYear,
                    { selectedYear = it },
                    Modifier.weight(1f).testTag("picker_year"),
                )
            }
        },
        confirmButton = {
            LinkButton(
                stringResource(Res.string.action_save),
                { onSave(selectedDay, selectedMonth, selectedYear) },
                enabled = selectedDay != null && selectedMonth != null,
            )
        },
        dismissButton = {
            Row {
                if (clearLabel != null) LinkButton(clearLabel, { onSave(null, null, null) })
                LinkButton(stringResource(Res.string.action_cancel), onDismiss)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

@Composable
private fun <T> PickerColumn(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
) {
    val start = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val plainColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.height(240.dp), state = listState) {
            itemsIndexed(options) { _, (value, label) ->
                val isSelected = value == selected
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) selectedColor else plainColor,
                                MaterialTheme.shapes.small,
                            ).clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                )
            }
        }
    }
}
