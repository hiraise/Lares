package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lares.client.data.location.PauseChoice
import app.lares.client.resources.Res
import app.lares.client.resources.ic_pause
import app.lares.client.resources.loc_pause_forever
import app.lares.client.resources.loc_pause_hour
import app.lares.client.resources.loc_pause_morning
import app.lares.client.resources.loc_pause_text
import app.lares.client.resources.loc_pause_title
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** «На 1 час · До утра · Пока не включу» (LOC-010); only for members with `location.pause_own`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseSheet(
    onPause: (PauseChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        PauseContent(onPause)
    }
}

@Composable
fun PauseContent(onPause: (PauseChoice) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("pause_sheet")
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Spacing.xl)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(stringResource(Res.string.loc_pause_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(Res.string.loc_pause_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListGroup {
            PauseChoice.entries.forEachIndexed { index, choice ->
                if (index > 0) ListDivider()
                ListRow(stringResource(choice.label), icon = Res.drawable.ic_pause, onClick = { onPause(choice) })
            }
        }
    }
}

val PauseChoice.label: StringResource
    get() =
        when (this) {
            PauseChoice.HOUR -> Res.string.loc_pause_hour
            PauseChoice.MORNING -> Res.string.loc_pause_morning
            PauseChoice.FOREVER -> Res.string.loc_pause_forever
        }
