package app.lares.client.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.today_family_map
import app.lares.client.resources.today_family_title
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.location.LocatedMember
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** «Семья сейчас» on Today (LOC-016): up to four members the viewer sees; null hides the block. */
data class TodayFamilyNowUi(
    val rows: List<LocatedMember>,
    /** Everybody's names, for unique initials. */
    val allNames: List<String> = rows.map { it.member.name },
)

/** «Семья сейчас» + «Карта», then the members with their place or address (mockup `Main.dc.html`); a tap opens the Map. */
@Composable
fun TodayFamilyNow(
    block: TodayFamilyNowUi,
    actions: TodayActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("today_family"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(Res.string.today_family_title))
            Spacer(Modifier.weight(1f))
            LinkButton(stringResource(Res.string.today_family_map), { actions.onMap(null) }, Modifier.testTag("today_map"))
        }
        ListGroup {
            block.rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider(inset = 60.dp)
                val member = row.member
                ListRow(
                    member.name,
                    Modifier.testTag("today_family_${member.memberId}"),
                    supporting = row.status,
                    leading = { Avatar(initialsFor(member.name, block.allNames), member.color, size = 32.dp) },
                    onClick = { actions.onMap(member.memberId) },
                )
            }
        }
    }
}
