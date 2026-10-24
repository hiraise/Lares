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
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
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
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.components.ColorSwatches
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.Spacing
import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The tag create/edit sheet. [id] is null for a new tag. */
data class TagSheet(
    val id: String? = null,
    val name: String = "",
    val color: PaletteColor = PaletteColor.BLUE,
    val archived: Boolean = false,
    val nameError: FieldError? = null,
    val error: UiError? = null,
)

/**
 * The logic of the tag sheet (TAG-001, TAG-006), shared by the Family screen and the task editor: tags are written
 * through sync, so it works offline. [onSaved] gets the id of a saved tag (a new one too).
 */
class TagEditor(
    private val scope: CoroutineScope,
    private val tagsRepository: TagsRepository,
    private val tagsWriter: TagsWriter,
) {
    private val mutableSheet = MutableStateFlow<TagSheet?>(null)
    private var onSaved: (String) -> Unit = {}

    val sheet: StateFlow<TagSheet?> = mutableSheet.asStateFlow()

    /** A new tag with the least-used color among [usedColors]. */
    fun new(
        usedColors: List<PaletteColor>,
        onSaved: (String) -> Unit = {},
    ) {
        this.onSaved = onSaved
        mutableSheet.value = TagSheet(color = PaletteColors.pickLeastUsed(usedColors))
    }

    fun edit(tag: Tag) {
        onSaved = {}
        mutableSheet.value = TagSheet(tag.id, tag.data.name, tag.data.color, tag.data.archived)
    }

    fun close() {
        mutableSheet.value = null
    }

    fun onName(value: String) = mutableSheet.update { it?.copy(name = value, nameError = null, error = null) }

    fun onColor(value: PaletteColor) = mutableSheet.update { it?.copy(color = value) }

    fun save() {
        val sheet = mutableSheet.value ?: return
        val name = sheet.name.trim()
        scope.launch {
            val all = tagsRepository.observeAll(includeArchived = true).first()
            val nameError = TagRules.nameError(name, all, sheet.id)
            if (nameError != null) {
                mutableSheet.update { it?.copy(nameError = nameError) }
                return@launch
            }
            val result =
                attempt {
                    val existing = all.firstOrNull { it.id == sheet.id }
                    if (existing == null) {
                        tagsWriter.create(name, sheet.color)
                    } else {
                        if (existing.data.name != name) tagsWriter.rename(existing.id, name)
                        if (existing.data.color != sheet.color) tagsWriter.recolor(existing.id, sheet.color)
                        existing.id
                    }
                }
            done(result)
            result.getOrNull()?.let(onSaved)
        }
    }

    fun setArchived(archived: Boolean) {
        val id = mutableSheet.value?.id ?: return
        scope.launch { done(attempt { tagsWriter.archive(id, archived) }) }
    }

    fun delete() {
        val id = mutableSheet.value?.id ?: return
        scope.launch { done(attempt { tagsWriter.delete(id) }) }
    }

    private fun done(result: Result<*>) {
        val failure = result.exceptionOrNull()
        if (failure == null) {
            mutableSheet.value = null
            return
        }
        mutableSheet.update { sheet ->
            if (failure is LocalWriteException) {
                val field = failure.fields["name"]
                if (field != null) {
                    sheet?.copy(nameError = FieldError.of(field))
                } else {
                    sheet?.copy(error = UiError.Api(failure.code))
                }
            } else {
                sheet?.copy(error = UiError.Api(ErrorCode.UNKNOWN))
            }
        }
    }
}

/** Create or edit a tag (TAG-001): name and palette color; existing tags can be archived or deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheetDialog(
    sheet: TagSheet,
    editor: TagEditor,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    // A new tag starts with typing its name.
    LaunchedEffect(Unit) { if (sheet.id == null) nameFocus.requestFocus() }
    ModalBottomSheet(
        onDismissRequest = editor::close,
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
                editor::onName,
                stringResource(Res.string.tag_name),
                error = sheet.nameError,
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
                onDone = editor::save,
                focusRequester = nameFocus,
            )
            Text(stringResource(Res.string.tag_color), style = MaterialTheme.typography.labelLarge)
            ColorSwatches(sheet.color, editor::onColor)
            ErrorText(sheet.error)
            PrimaryButton(stringResource(Res.string.action_save), editor::save)
            if (sheet.id != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LinkButton(
                        stringResource(if (sheet.archived) Res.string.tag_unarchive else Res.string.tag_archive),
                        { editor.setArchived(!sheet.archived) },
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
                editor.delete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}
