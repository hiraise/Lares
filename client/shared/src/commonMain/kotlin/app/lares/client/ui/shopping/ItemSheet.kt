package app.lares.client.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.lares.client.data.Tag
import app.lares.client.resources.Res
import app.lares.client.resources.action_save
import app.lares.client.resources.ic_category
import app.lares.client.resources.ic_sell
import app.lares.client.resources.shopping_discard_confirm
import app.lares.client.resources.shopping_discard_keep
import app.lares.client.resources.shopping_discard_text
import app.lares.client.resources.shopping_discard_title
import app.lares.client.resources.shopping_item_department
import app.lares.client.resources.shopping_item_department_hint
import app.lares.client.resources.shopping_item_name
import app.lares.client.resources.shopping_item_note
import app.lares.client.resources.shopping_item_quantity
import app.lares.client.resources.shopping_item_quantity_hint
import app.lares.client.resources.shopping_item_remove
import app.lares.client.resources.shopping_item_tags
import app.lares.client.resources.shopping_item_title
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.common.text
import app.lares.client.ui.components.ChoiceDialog
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.tasks.EditorSheet
import app.lares.client.ui.tasks.TagChips
import app.lares.client.ui.tasks.TagsSheet
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.ProductCategory
import org.jetbrains.compose.resources.stringResource

/** What the item sheet can do; defaults do nothing (tests). */
class ItemSheetActions(
    val onEdit: ((ItemForm) -> ItemForm) -> Unit = {},
    val onPicker: (ItemPicker?) -> Unit = {},
    val onDepartment: (ProductCategory) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onNewTag: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onRemove: () -> Unit = {},
    /** Swipe, scrim or back: closes, or asks first when something changed. */
    val onRequestClose: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val onDiscard: () -> Unit = {},
)

/**
 * The item sheet (plan M2-05, SHP-002, SHP-006, SHP-010): name, quantity, note, department (remembered for all lists),
 * tags, who added and bought; «Сохранить», «Убрать из списка».
 */
@Composable
fun ShoppingItemSheet(
    sheet: ItemSheetState,
    tags: List<Tag>,
    canManageTags: Boolean,
    actions: ItemSheetActions = ItemSheetActions(),
) {
    val form = sheet.form
    val time = rememberTimeFormatter()
    EditorSheet(stringResource(Res.string.shopping_item_title), actions.onRequestClose, canClose = { !sheet.changed }) {
        Column(Modifier.testTag("shopping_item_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            FormField(
                form.name,
                { value -> actions.onEdit { it.copy(name = value) } },
                stringResource(Res.string.shopping_item_name),
                capitalization = KeyboardCapitalization.Sentences,
            )
            FormField(
                form.quantity,
                { value -> actions.onEdit { it.copy(quantity = value) } },
                stringResource(Res.string.shopping_item_quantity),
                hint = stringResource(Res.string.shopping_item_quantity_hint),
            )
            FormField(
                form.note,
                { value -> actions.onEdit { it.copy(note = value) } },
                stringResource(Res.string.shopping_item_note),
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
                singleLine = false,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ListGroup {
                    ListRow(
                        stringResource(Res.string.shopping_item_department),
                        Modifier.testTag("shopping_item_department"),
                        icon = Res.drawable.ic_category,
                        trailingText = stringResource(ShoppingLogic.categoryLabel(form.category)),
                        showChevron = true,
                        onClick = { actions.onPicker(ItemPicker.DEPARTMENT) },
                    )
                    ListDivider()
                    val selected = tags.filter { it.id in form.tagIds }
                    ListRow(
                        stringResource(Res.string.shopping_item_tags),
                        Modifier.testTag("shopping_item_tags"),
                        icon = Res.drawable.ic_sell,
                        showChevron = true,
                        onClick = { actions.onPicker(ItemPicker.TAGS) },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                TagChips(selected)
                            }
                        },
                    )
                }
                Text(
                    stringResource(Res.string.shopping_item_department_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            val info =
                listOfNotNull(
                    ShoppingLogic.addedLine(sheet.item, time),
                    ShoppingLogic.boughtLine(sheet.item, time),
                    ShoppingLogic.boughtTimes(sheet.item),
                )
            if (info.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    info.forEach {
                        Text(it.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            PrimaryButton(
                stringResource(Res.string.action_save),
                actions.onSave,
                Modifier.testTag("shopping_item_save"),
                enabled = sheet.canSave,
            )
            LinkButton(
                stringResource(Res.string.shopping_item_remove),
                actions.onRemove,
                Modifier.fillMaxWidth().testTag("shopping_item_remove"),
            )
        }
    }
    when (sheet.picker) {
        ItemPicker.DEPARTMENT -> {
            ChoiceDialog(
                title = stringResource(Res.string.shopping_item_department),
                options = ProductCategory.entries,
                selected = form.category,
                label = { stringResource(ShoppingLogic.categoryLabel(it)) },
                onSelect = actions.onDepartment,
                onDismiss = { actions.onPicker(null) },
            )
        }

        ItemPicker.TAGS -> {
            TagsSheet(ShoppingLogic.tagChoices(tags, form.tagIds), form.tagIds, canManageTags, actions.onToggleTag, actions.onNewTag) {
                actions.onPicker(null)
            }
        }

        null -> {}
    }
    if (sheet.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.shopping_discard_title),
            text = stringResource(Res.string.shopping_discard_text),
            confirmLabel = stringResource(Res.string.shopping_discard_confirm),
            dismissLabel = stringResource(Res.string.shopping_discard_keep),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onKeepEditing,
            destructive = true,
        )
    }
}
