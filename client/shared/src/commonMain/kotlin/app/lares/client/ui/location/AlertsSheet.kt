package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlaceAlert
import app.lares.client.data.location.PlacesWriter
import app.lares.client.resources.Res
import app.lares.client.resources.action_save
import app.lares.client.resources.alerts_here_now
import app.lares.client.resources.alerts_no_members
import app.lares.client.resources.alerts_no_places
import app.lares.client.resources.alerts_text
import app.lares.client.resources.alerts_title
import app.lares.client.ui.components.CheckRow
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** Whose alerts the sheet edits: about one member (rows are places) or on one place (rows are members). */
sealed interface AlertsTarget {
    data class Member(
        val memberId: String,
    ) : AlertsTarget

    data class Place(
        val placeId: String,
    ) : AlertsTarget
}

/** A check row: a place for a member target, a member for a place target; [hereNow] = «сейчас здесь». */
data class AlertRow(
    val id: String,
    val title: String,
    val checked: Boolean,
    val hereNow: Boolean = false,
)

/** The open alerts sheet: [subject] is the member's or the place's name. */
data class AlertsSheetUi(
    val target: AlertsTarget,
    val subject: String,
    val rows: List<AlertRow>,
)

/**
 * «Сообщать: прибытие и уход» (LOC-021): the rows of the sheet and the alert rows a save writes. The viewer's own
 * alerts are one row per watched member with the full set of places ([PlacesWriter.setAlert]).
 */
object AlertsLogic {
    /** The places for alerts about [member], by name; the place the member is in now is marked. */
    fun memberRows(
        member: MemberOnMap,
        places: List<Place>,
        alerts: List<PlaceAlert>,
    ): List<AlertRow> {
        val on = placesFor(member.memberId, alerts)
        return places
            .sortedBy { it.data.name.lowercase() }
            .map { AlertRow(it.id, it.data.name, it.id in on, hereNow = member.place?.id == it.id) }
    }

    /** The members the viewer sees (not the viewer) for alerts on [placeId], by name. */
    fun placeRows(
        placeId: String,
        members: List<MemberOnMap>,
        me: String?,
        alerts: List<PlaceAlert>,
    ): List<AlertRow> =
        members
            .filter { it.memberId != me }
            .sortedBy { it.name.lowercase() }
            .map { AlertRow(it.memberId, it.name, placeId in placesFor(it.memberId, alerts), hereNow = it.place?.id == placeId) }

    /** The new full set of places per member that a save writes; only members whose set changes. */
    fun changes(
        target: AlertsTarget,
        rows: List<AlertRow>,
        selected: Set<String>,
        alerts: List<PlaceAlert>,
    ): Map<String, Set<String>> =
        when (target) {
            is AlertsTarget.Member -> {
                val current = placesFor(target.memberId, alerts)
                // Places not in the rows (deleted meanwhile) are dropped; the server ignores them anyway.
                val next = rows.map { it.id }.filter { it in selected }.toSet()
                if (next == current) emptyMap() else mapOf(target.memberId to next)
            }

            is AlertsTarget.Place -> {
                rows
                    .mapNotNull { row ->
                        val current = placesFor(row.id, alerts)
                        val next = if (row.id in selected) current + target.placeId else current - target.placeId
                        if (next == current) null else row.id to next
                    }.toMap()
            }
        }

    /** Writes [changes] (offline, through sync); an empty set turns the member's alerts off. */
    suspend fun save(
        writer: PlacesWriter,
        changes: Map<String, Set<String>>,
    ) {
        for ((memberId, placeIds) in changes) writer.setAlert(memberId, placeIds)
    }

    private fun placesFor(
        memberId: String,
        alerts: List<PlaceAlert>,
    ): Set<String> =
        alerts
            .firstOrNull { it.data.memberId == memberId }
            ?.data
            ?.placeIds
            .orEmpty()
}

/**
 * The alerts sheet (plan M4-10): «Сообщать: прибытие и уход», the subject, check rows and «Сохранить». From the
 * member card and the person page (rows are places) or the place sheet (rows are members).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    sheet: AlertsSheetUi,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(sheet.target) {
        mutableStateOf(
            sheet.rows
                .filter { it.checked }
                .map { it.id }
                .toSet(),
        )
    }
    val hereNow = stringResource(Res.string.alerts_here_now)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .testTag("alerts_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.alerts_title), style = MaterialTheme.typography.headlineSmall)
                Text(sheet.subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("alerts_subject"))
                Text(
                    stringResource(Res.string.alerts_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sheet.rows.isEmpty()) {
                val empty = if (sheet.target is AlertsTarget.Member) Res.string.alerts_no_places else Res.string.alerts_no_members
                Text(
                    stringResource(empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("alerts_empty"),
                )
            } else {
                ListGroup {
                    sheet.rows.forEachIndexed { index, row ->
                        if (index > 0) ListDivider(inset = 16.dp)
                        CheckRow(
                            row.title,
                            checked = row.id in selected,
                            onCheckedChange = { on -> selected = if (on) selected + row.id else selected - row.id },
                            modifier = Modifier.testTag("alert_${row.id}"),
                            supporting = if (row.hereNow) hereNow else null,
                        )
                    }
                }
                PrimaryButton(stringResource(Res.string.action_save), { onSave(selected) }, Modifier.testTag("alerts_save"))
            }
        }
    }
}
