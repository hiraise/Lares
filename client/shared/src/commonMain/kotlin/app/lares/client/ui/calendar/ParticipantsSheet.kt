package app.lares.client.ui.calendar

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
import app.lares.client.resources.Res
import app.lares.client.resources.action_done
import app.lares.client.resources.cal_participant_member
import app.lares.client.resources.cal_participants_title
import app.lares.client.resources.cal_whole_family
import app.lares.client.resources.ic_group
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.CheckRow
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.tasks.EditorSheet
import org.jetbrains.compose.resources.stringResource

/**
 * «Кого касается» (CAL-002): «Вся семья» first (exclusive: it clears the persons; selected when none is), then every
 * person — members first, then relatives, by name. Changes apply at once; «Готово» closes.
 */
@Composable
fun ParticipantsSheet(
    persons: List<ParticipantChoice>,
    allNames: List<String>,
    selected: Set<String>,
    onWholeFamily: () -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.cal_participants_title), onDismiss) {
        val chosen = selected.intersect(persons.map { it.id }.toSet())
        ListGroup {
            CheckRow(
                stringResource(Res.string.cal_whole_family),
                checked = chosen.isEmpty(),
                onCheckedChange = { onWholeFamily() },
                modifier = Modifier.testTag("participant_all"),
                leading = {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { AppIcon(Res.drawable.ic_group, null, size = 18.dp) }
                },
            )
            val member = stringResource(Res.string.cal_participant_member)
            persons.forEach { person ->
                ListDivider(inset = 62.dp)
                CheckRow(
                    person.name,
                    checked = person.id in chosen,
                    onCheckedChange = { onToggle(person.id) },
                    supporting = person.relation?.takeIf { it.isNotBlank() } ?: member.takeIf { person.member },
                    modifier = Modifier.testTag("participant_${person.id}"),
                    leading = { Avatar(initialsFor(person.name, allNames), person.color, size = 32.dp) },
                )
            }
        }
        PrimaryButton(stringResource(Res.string.action_done), onDismiss)
    }
}
