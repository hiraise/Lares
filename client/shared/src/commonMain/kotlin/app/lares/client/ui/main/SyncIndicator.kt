package app.lares.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.ic_cloud_done
import app.lares.client.resources.ic_cloud_off
import app.lares.client.resources.ic_cloud_upload
import app.lares.client.resources.ic_error
import app.lares.client.resources.sync_error
import app.lares.client.resources.sync_offline
import app.lares.client.resources.sync_offline_pending
import app.lares.client.resources.sync_pending
import app.lares.client.resources.sync_synced
import app.lares.client.resources.sync_syncing
import app.lares.client.sync.SyncState
import app.lares.client.sync.SyncStatus
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.theme.tabular
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * FND-043 in the header: `cloud_done` when synced; a chip with `cloud_upload` / `cloud_off` and the pending count
 * while changes wait; `error` in the error color. Tap opens the sync sheet.
 */
@Composable
fun SyncIndicator(
    status: SyncStatus,
    onClick: () -> Unit,
) {
    val description = syncDescription(status)
    when {
        status.state == SyncState.SYNCED -> {
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                AppIcon(Res.drawable.ic_cloud_done, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        status.state == SyncState.ERROR -> {
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                AppIcon(Res.drawable.ic_error, description, tint = MaterialTheme.colorScheme.error)
            }
        }

        else -> {
            val icon = if (status.state == SyncState.OFFLINE) Res.drawable.ic_cloud_off else Res.drawable.ic_cloud_upload
            Row(
                Modifier
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                    .clickable(onClick = onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        role = Role.Button
                    }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppIcon(icon, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.pendingCount > 0) {
                    Text(
                        status.pendingCount.toString(),
                        style =
                            MaterialTheme.typography.bodySmall
                                .copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                ).tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One sentence about the sync state, for screen readers and the sheet. */
@Composable
fun syncDescription(status: SyncStatus): String {
    val pending = status.pendingCount.toInt()
    return when (status.state) {
        SyncState.SYNCED -> {
            stringResource(Res.string.sync_synced)
        }

        SyncState.SYNCING -> {
            stringResource(Res.string.sync_syncing)
        }

        SyncState.PENDING -> {
            pluralStringResource(Res.plurals.sync_pending, pending, pending)
        }

        SyncState.OFFLINE -> {
            if (pending >
                0
            ) {
                pluralStringResource(Res.plurals.sync_offline_pending, pending, pending)
            } else {
                stringResource(Res.string.sync_offline)
            }
        }

        SyncState.ERROR -> {
            stringResource(Res.string.sync_error)
        }
    }
}
