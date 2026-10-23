package app.lares.client.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.lares.client.resources.Res
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_save
import app.lares.client.resources.tag_archive
import app.lares.client.resources.tag_color
import app.lares.client.resources.tag_delete_text
import app.lares.client.resources.tag_delete_title
import app.lares.client.resources.tag_name
import app.lares.client.resources.tag_new
import app.lares.client.resources.tag_title
import app.lares.client.resources.tag_unarchive
import app.lares.client.ui.components.ColorSwatches
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** Create or edit a tag (TAG-001): name and palette color; existing tags can be archived or deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheetDialog(
    sheet: TagSheet,
    viewModel: FamilyViewModel,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    // A new tag starts with typing its name.
    LaunchedEffect(Unit) { if (sheet.id == null) nameFocus.requestFocus() }
    ModalBottomSheet(
        onDismissRequest = viewModel::closeTagSheet,
        // Fully expanded, so the sheet moves up with the keyboard instead of hiding behind it.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(
                stringResource(if (sheet.id == null) Res.string.tag_new else Res.string.tag_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            FormField(
                sheet.name,
                viewModel::onTagName,
                stringResource(Res.string.tag_name),
                error = sheet.nameError,
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
                onDone = viewModel::saveTag,
                focusRequester = nameFocus,
            )
            Text(stringResource(Res.string.tag_color), style = MaterialTheme.typography.labelLarge)
            ColorSwatches(sheet.color, viewModel::onTagColor)
            ErrorText(sheet.error)
            PrimaryButton(stringResource(Res.string.action_save), viewModel::saveTag)
            if (sheet.id != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LinkButton(
                        stringResource(if (sheet.archived) Res.string.tag_unarchive else Res.string.tag_archive),
                        { viewModel.setTagArchived(!sheet.archived) },
                    )
                    LinkButton(stringResource(Res.string.action_delete), { confirmDelete = true }, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(Res.string.tag_delete_title, sheet.name),
            text = stringResource(Res.string.tag_delete_text),
            confirmLabel = stringResource(Res.string.action_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.deleteTag()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}
