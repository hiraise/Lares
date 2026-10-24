package app.lares.client.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lares.client.data.Tag
import app.lares.client.resources.Res
import app.lares.client.resources.action_done
import app.lares.client.resources.ic_check
import app.lares.client.resources.task_field_tags
import app.lares.client.resources.task_new_tag
import app.lares.client.resources.tasks_tags_none
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import org.jetbrains.compose.resources.stringResource

/**
 * Tags of the task (TAG-002): active tags and archived ones already on the task, as selectable chips. With
 * `tags.manage`, "+ New tag" opens the tag sheet of the Family screen; the new tag is selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsSheet(
    tags: List<Tag>,
    selected: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit,
    onNewTag: () -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.task_field_tags), onDismiss) {
        if (tags.isEmpty() && !canManage) {
            Text(
                stringResource(Res.string.tasks_tags_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            tags.forEach { tag -> SelectableTag(tag, tag.id in selected) { onToggle(tag.id) } }
            if (canManage) AppFilterChip(false, stringResource(Res.string.task_new_tag), onNewTag, Modifier.testTag("new_tag"))
        }
        PrimaryButton(stringResource(Res.string.action_done), onDismiss)
    }
}

/** A tag chip that can be picked: selected = palette container with a check, otherwise an outline. */
@Composable
private fun SelectableTag(
    tag: Tag,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val tones = tag.data.color.tones()
    val shape = RoundedCornerShape(12.dp)
    val look =
        if (selected) {
            Modifier.background(tones.container, shape)
        } else {
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(look)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("tag_${tag.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) AppIcon(Res.drawable.ic_check, null, size = 16.dp, tint = tones.onContainer)
        Text(
            tag.data.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tones.onContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
