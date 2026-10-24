package app.lares.client.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.client.data.tasks.TaskView
import app.lares.client.resources.Res
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_checklist
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.task_mark
import app.lares.client.resources.task_unmark
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.client.ui.theme.tones
import org.jetbrains.compose.resources.stringResource

/**
 * One row of a task list. [done]: the row is ticked (a "done today" row or a done one-off). [canToggle]: the member
 * may tick it (open) or undo it (done); otherwise the checkbox looks disabled and a tap explains why.
 */
data class TaskRowUi(
    val key: String,
    val view: TaskView,
    val meta: TaskMeta,
    val done: Boolean,
    val canToggle: Boolean,
)

/** Round task checkbox (visual-style.md): 1.5 dp border, `error` when overdue, filled `primary` with a check when done. */
@Composable
fun TaskCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    overdue: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    // Stays tappable when not allowed: the tap shows why (plan M1-05).
    Box(
        modifier
            .size(48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val circle = Modifier.size(size).alpha(if (enabled) 1f else DISABLED_ALPHA)
        if (checked) {
            Box(circle.background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(Res.drawable.ic_check, null, size = size * 0.75f, tint = colors.onPrimary)
            }
        } else {
            Box(circle.border(1.5.dp, if (overdue) colors.error else colors.onSurfaceVariant, CircleShape))
        }
    }
}

/** A tag chip: 12 sp bold, radius 8, palette container and on-container. */
@Composable
fun TagChip(
    tag: Tag,
    modifier: Modifier = Modifier,
) {
    val tones = tag.data.color.tones()
    Text(
        tag.data.name,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        color = tones.onContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(tones.container, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Up to [max] tag chips and "+N" for the rest. */
@Composable
fun TagChips(
    tags: List<Tag>,
    max: Int = 2,
) {
    tags.take(max).forEach { TagChip(it) }
    if (tags.size > max) {
        Text(
            "+${tags.size - max}",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Assignee avatars: up to 2 overlapping and "+N"; nothing for "anyone" tasks. [allNames] decides two-letter initials. */
@Composable
fun AssigneeAvatars(
    assignees: List<MemberView>,
    modifier: Modifier = Modifier,
    allNames: List<String> = assignees.map { it.name },
    size: Dp = 28.dp,
) {
    if (assignees.isEmpty()) return
    val shown = assignees.take(2)
    val extra = assignees.size - shown.size
    val description = assignees.joinToString { it.name }
    Row(modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, member ->
            val overlap = if (index == 0) Modifier else Modifier.offset(x = (-8 * index).dp)
            Avatar(
                initialsFor(member.name, allNames),
                member.color,
                size = size,
                modifier =
                    overlap
                        .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                        .padding(1.dp),
            )
        }
        if (extra > 0) {
            Box(
                Modifier
                    .offset(x = (-8 * shown.size).dp)
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$extra", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A task row (mockup `Tasks.dc.html`): checkbox, title, meta line with tags and checklist progress, assignees. */
@Composable
fun TaskRow(
    row: TaskRowUi,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    allNames: List<String> = emptyList(),
) {
    val view = row.view
    val colors = MaterialTheme.colorScheme
    val overdue = row.meta.kind == MetaKind.OVERDUE && !row.done
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = Spacing.l, top = 2.dp, bottom = 2.dp)
            .testTag("task_row_${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(if (row.done) Res.string.task_unmark else Res.string.task_mark, view.data.title)
        TaskCheckbox(
            checked = row.done,
            onToggle = onToggle,
            label = label,
            overdue = overdue,
            enabled = row.canToggle,
            modifier = Modifier.testTag("task_check_${row.key}"),
        )
        Column(
            Modifier.weight(1f).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                view.data.title,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (row.done) TextDecoration.LineThrough else null,
                    ),
                color = if (row.done) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.testTag("task_title_${row.key}"),
            )
            MetaLine(row, overdue)
        }
        if (view.assignees.isNotEmpty()) {
            Box(Modifier.width(Spacing.m))
            AssigneeAvatars(view.assignees, allNames = allNames.ifEmpty { view.assignees.map { it.name } })
        }
    }
}

@Composable
private fun MetaLine(
    row: TaskRowUi,
    overdue: Boolean,
) {
    val view = row.view
    val text = row.meta.text
    val showChecklist = view.checklistTotal > 0 && !row.done
    if (text == null && view.tags.isEmpty() && !showChecklist) return
    val colors = MaterialTheme.colorScheme
    val color = if (overdue) colors.error else colors.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (text != null) {
            if (row.meta.kind == MetaKind.ROUTINE && !row.done) AppIcon(Res.drawable.ic_repeat, null, size = 16.dp, tint = color)
            Text(
                text.text(),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (overdue) FontWeight.SemiBold else MaterialTheme.typography.bodySmall.fontWeight,
                    ),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(
                            1f,
                            fill = false,
                        ).testTag(if (overdue) "task_meta_overdue_${row.key}" else "task_meta_${row.key}"),
            )
        }
        if (!row.done) TagChips(view.tags)
        if (showChecklist) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AppIcon(Res.drawable.ic_checklist, null, size = 16.dp, tint = colors.onSurfaceVariant)
                Text(
                    "${view.checklistDone}/${view.checklistTotal}",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Alpha of a checkbox the member may not tick (plan M1-05). */
const val DISABLED_ALPHA = 0.38f
