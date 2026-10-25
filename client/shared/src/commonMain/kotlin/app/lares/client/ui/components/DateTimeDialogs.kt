package app.lares.client.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_clear
import app.lares.client.resources.action_done
import app.lares.client.resources.task_field_time
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Material 3 date picker of the editors; with [canClear] a "Clear" button (or [clearLabel], e.g. «Без конца») picks
 * null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(
    initial: LocalDate,
    canClear: Boolean,
    onPick: (LocalDate?) -> Unit,
    clearLabel: String? = null,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinkButton(stringResource(Res.string.action_done), {
                pickerState.selectedDateMillis?.let {
                    onPick(
                        Instant
                            .fromEpochMilliseconds(it)
                            .toLocalDateTime(TimeZone.UTC)
                            .date,
                    )
                }
                onDismiss()
            })
        },
        dismissButton = {
            Row {
                if (canClear) {
                    LinkButton(clearLabel ?: stringResource(Res.string.action_clear), {
                        onPick(null)
                        onDismiss()
                    })
                }
                LinkButton(stringResource(Res.string.action_cancel), onDismiss)
            }
        },
    ) { DatePicker(pickerState) }
}

/** Material 3 time picker (24 h by the system setting); with [canClear] (default: a time is set) "Clear" picks null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    initial: LocalTime?,
    onPick: (LocalTime?) -> Unit,
    canClear: Boolean = initial != null,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = initial?.hour ?: DEFAULT_HOUR, initialMinute = initial?.minute ?: 0)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.task_field_time), style = MaterialTheme.typography.labelLarge) },
        confirmButton = {
            LinkButton(stringResource(Res.string.action_done), {
                onPick(LocalTime(pickerState.hour, pickerState.minute))
                onDismiss()
            })
        },
        dismissButton = {
            Row {
                if (canClear) {
                    LinkButton(stringResource(Res.string.action_clear), {
                        onPick(null)
                        onDismiss()
                    })
                }
                LinkButton(stringResource(Res.string.action_cancel), onDismiss)
            }
        },
    ) { TimePicker(pickerState) }
}

private const val DEFAULT_HOUR = 9
