package app.lares.client.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lares.client.resources.Res
import app.lares.client.resources.action_got_it
import app.lares.client.resources.ic_error
import app.lares.client.resources.sync_last
import app.lares.client.resources.sync_never
import app.lares.client.resources.sync_now
import app.lares.client.resources.sync_rejected_title
import app.lares.client.resources.sync_sheet_title
import app.lares.client.sync.Rejection
import app.lares.client.sync.SyncStatus
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.message
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** Sync details (FND-043): state, last success, pending changes, unseen rejections, "Sync now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(
    status: SyncStatus,
    rejections: List<Rejection>,
    syncingNow: Boolean,
    onSyncNow: () -> Unit,
    onRejectionsSeen: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val formatTime = rememberTimeFormatter()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape =
            MaterialTheme.shapes.extraLarge.copy(
                bottomStart =
                    androidx.compose.foundation.shape
                        .CornerSize(0),
                bottomEnd =
                    androidx.compose.foundation.shape
                        .CornerSize(0),
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.sync_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(syncDescription(status), style = MaterialTheme.typography.titleMedium)
                val last = status.lastSuccessAt
                Text(
                    if (last == null) stringResource(Res.string.sync_never) else stringResource(Res.string.sync_last, formatTime(last)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.lastError?.let {
                    Text(UiError.Api(it).message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (rejections.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.sync_rejected_title))
                ListGroup {
                    rejections.forEachIndexed { index, rejection ->
                        if (index > 0) ListDivider()
                        ListRow(
                            title = stringResource(rejection.code.messageRes()),
                            supporting = formatTime(rejection.at),
                            icon = Res.drawable.ic_error,
                            iconTint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TonalButton(stringResource(Res.string.action_got_it), { onRejectionsSeen(rejections.map { it.id }) })
            }
            PrimaryButton(stringResource(Res.string.sync_now), onSyncNow, loading = syncingNow)
        }
    }
}
