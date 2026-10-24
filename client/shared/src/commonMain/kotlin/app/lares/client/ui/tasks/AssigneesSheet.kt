package app.lares.client.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lares.client.data.MemberView
import app.lares.client.resources.Res
import app.lares.client.resources.action_done
import app.lares.client.resources.ic_group
import app.lares.client.resources.task_anyone
import app.lares.client.resources.task_anyone_hint
import app.lares.client.resources.task_field_assignees
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.CheckRow
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.initialsFor
import org.jetbrains.compose.resources.stringResource

/**
 * Assignees (TSK-002): "Anyone" first (exclusive: it clears the members), then the members who see tasks. Changes
 * apply at once; "Done" closes the sheet.
 */
@Composable
fun AssigneesSheet(
    members: List<MemberView>,
    allNames: List<String>,
    selected: Set<String>,
    onAnyone: () -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.task_field_assignees), onDismiss) {
        val chosen = selected.intersect(members.map { it.id }.toSet())
        ListGroup {
            CheckRow(
                stringResource(Res.string.task_anyone),
                checked = chosen.isEmpty(),
                onCheckedChange = { onAnyone() },
                supporting = stringResource(Res.string.task_anyone_hint),
                modifier = Modifier.testTag("assignee_anyone"),
                leading = {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { AppIcon(Res.drawable.ic_group, null, size = 18.dp) }
                },
            )
            members.forEach { member ->
                ListDivider(inset = 62.dp)
                CheckRow(
                    member.name,
                    checked = member.id in chosen,
                    onCheckedChange = { onToggle(member.id) },
                    modifier = Modifier.testTag("assignee_${member.id}"),
                    leading = { Avatar(initialsFor(member.name, allNames), member.color, size = 32.dp) },
                )
            }
        }
        PrimaryButton(stringResource(Res.string.action_done), onDismiss)
    }
}
