package app.lares.client.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lares.client.resources.Res
import app.lares.client.resources.task_field_reminder
import app.lares.client.resources.task_reminder_none
import app.lares.client.ui.common.text
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** A bottom sheet of the task editor: title, then [content] on the screen background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

/** The reminder choices (plan M1-06); a choice applies at once and closes the sheet. */
@Composable
fun ReminderSheet(
    hasTime: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.task_field_reminder), onDismiss) {
        ReminderOptions(hasTime, selected) {
            onSelect(it)
            onDismiss()
        }
    }
}

/** "None" and the options for tasks with or without a time; a stored value outside them is shown too. */
@Composable
fun ReminderOptions(
    hasTime: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val options = TaskForm.reminderOptions(hasTime)
    val all: List<Int?> = listOf(null) + options + listOfNotNull(selected?.takeIf { it !in options })
    ListGroup {
        all.forEachIndexed { index, minutes ->
            if (index > 0) ListDivider(inset = Spacing.l)
            val label =
                if (minutes == null) stringResource(Res.string.task_reminder_none) else TasksLogic.reminder(minutes, hasTime).text()
            ListRow(
                label,
                modifier = Modifier.testTag("reminder_${minutes ?: "none"}"),
                onClick = { onSelect(minutes) },
                trailing = { RadioButton(selected = minutes == selected, onClick = null) },
            )
        }
    }
}
