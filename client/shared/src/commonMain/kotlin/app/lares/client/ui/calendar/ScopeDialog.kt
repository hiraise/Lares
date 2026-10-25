package app.lares.client.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.cal_scope_all
import app.lares.client.resources.cal_scope_following
import app.lares.client.resources.cal_scope_this
import app.lares.client.ui.components.LinkButton
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** What a change of a repeating event applies to (CAL-015). */
enum class RepeatScope { THIS, FOLLOWING, ALL }

/**
 * Opens the event editor (M3-06): [id] null = a new event on [date] (timed: at [hour], else the next full hour),
 * [memorable] a memorable date, with [personId] as the participant and [tagId] as a tag; an existing one is changed for
 * [scope] from [occurrence].
 */
data class EventEditorArgs(
    val id: String? = null,
    val occurrence: LocalDate? = null,
    val scope: RepeatScope = RepeatScope.ALL,
    val date: LocalDate? = null,
    val hour: Int? = null,
    val memorable: Boolean = false,
    val personId: String? = null,
    val tagId: String? = null,
)

/** «Только это · Это и следующие · Все» with radio buttons; [confirmLabel] applies the choice. */
@Composable
fun ScopeDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (RepeatScope) -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    var selected by rememberSaveable { mutableStateOf(RepeatScope.THIS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                RepeatScope.entries.forEach { scope ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(scope == selected, role = Role.RadioButton, onClick = { selected = scope })
                            .testTag("scope_${scope.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = scope == selected, onClick = null)
                        Text(stringResource(scope.label()), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            LinkButton(
                confirmLabel,
                { onConfirm(selected) },
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("scope_confirm"),
            )
        },
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

private fun RepeatScope.label() =
    when (this) {
        RepeatScope.THIS -> Res.string.cal_scope_this
        RepeatScope.FOLLOWING -> Res.string.cal_scope_following
        RepeatScope.ALL -> Res.string.cal_scope_all
    }
