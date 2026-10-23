package app.familyhub.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.group_administrators
import app.familyhub.client.resources.ic_account_balance_wallet
import app.familyhub.client.resources.ic_chevron_right
import app.familyhub.client.resources.ic_cloud_done
import app.familyhub.client.resources.ic_cloud_off
import app.familyhub.client.resources.ic_cloud_upload
import app.familyhub.client.resources.ic_devices
import app.familyhub.client.resources.ic_error
import app.familyhub.client.resources.ic_group
import app.familyhub.client.resources.ic_help
import app.familyhub.client.resources.ic_location_on
import app.familyhub.client.resources.ic_notifications
import app.familyhub.client.resources.ic_sell
import app.familyhub.client.resources.ic_settings
import app.familyhub.client.resources.more_devices
import app.familyhub.client.resources.more_family_groups
import app.familyhub.client.resources.more_family_members
import app.familyhub.client.resources.more_help
import app.familyhub.client.resources.more_notifications
import app.familyhub.client.resources.more_settings
import app.familyhub.client.resources.more_settings_hint
import app.familyhub.client.resources.more_soon
import app.familyhub.client.resources.more_sync
import app.familyhub.client.resources.more_sync_saved_at
import app.familyhub.client.resources.more_tags_count
import app.familyhub.client.resources.more_tags_none
import app.familyhub.client.resources.nav_more
import app.familyhub.client.resources.placeholder_coming
import app.familyhub.client.resources.section_budget
import app.familyhub.client.resources.section_family
import app.familyhub.client.resources.section_map
import app.familyhub.client.resources.section_tags
import app.familyhub.client.sync.SyncState
import app.familyhub.client.ui.common.rememberTimeFormatter
import app.familyhub.client.ui.components.AppIcon
import app.familyhub.client.ui.components.Avatar
import app.familyhub.client.ui.components.ListDivider
import app.familyhub.client.ui.components.ListGroup
import app.familyhub.client.ui.components.ListRow
import app.familyhub.client.ui.components.ScreenHeader
import app.familyhub.client.ui.components.initialsFor
import app.familyhub.client.ui.family.FamilyTab
import app.familyhub.client.ui.theme.Spacing
import app.familyhub.client.ui.theme.tones
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.Section
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** FND-064, mockup `More.dc.html`: profile card, section tiles the member may open, settings list. */
@Composable
fun MoreScreen(
    state: MainUiState,
    navigation: MainNavigation,
    onOpenSync: () -> Unit,
) {
    val formatTime = rememberTimeFormatter()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(Res.string.nav_more))
        Column(
            Modifier.padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ProfileCard(state, navigation.onProfile)
            val tiles =
                buildList {
                    if (Section.MAP in state.sections) {
                        add(
                            Tile(Res.drawable.ic_location_on, stringResource(Res.string.section_map), null, true, PaletteColor.TEAL) {
                                navigation.onSection(Section.MAP)
                            },
                        )
                    }
                    if (Section.BUDGET in state.sections) {
                        add(
                            Tile(
                                Res.drawable.ic_account_balance_wallet,
                                stringResource(Res.string.section_budget),
                                null,
                                true,
                                PaletteColor.BLUE,
                            ) {
                                navigation.onSection(Section.BUDGET)
                            },
                        )
                    }
                    // Family and Tags are visible to every member; editing follows permissions (plan M0-08).
                    add(
                        Tile(
                            Res.drawable.ic_group,
                            stringResource(Res.string.section_family),
                            listOf(
                                pluralStringResource(Res.plurals.more_family_members, state.memberCount, state.memberCount),
                                pluralStringResource(Res.plurals.more_family_groups, state.groupCount, state.groupCount),
                            ).joinToString(", "),
                            true,
                            PaletteColor.PINK,
                        ) {
                            navigation.onFamily(FamilyTab.MEMBERS)
                        },
                    )
                    add(
                        Tile(
                            Res.drawable.ic_sell,
                            stringResource(Res.string.section_tags),
                            if (state.tagCount == 0) {
                                stringResource(Res.string.more_tags_none)
                            } else {
                                pluralStringResource(Res.plurals.more_tags_count, state.tagCount, state.tagCount)
                            },
                            true,
                            PaletteColor.YELLOW,
                        ) {
                            navigation.onFamily(FamilyTab.TAGS)
                        },
                    )
                }
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { TileCard(it, Modifier.weight(1f)) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            ListGroup {
                ListRow(
                    stringResource(Res.string.more_settings),
                    icon = Res.drawable.ic_settings,
                    trailingText = stringResource(Res.string.more_settings_hint),
                    onClick = navigation.onSettings,
                )
                ListDivider()
                ListRow(
                    stringResource(Res.string.more_notifications),
                    icon = Res.drawable.ic_notifications,
                    trailingText = stringResource(Res.string.more_soon),
                    enabled = false,
                    onClick = {},
                )
                ListDivider()
                val last = state.sync.lastSuccessAt
                ListRow(
                    stringResource(Res.string.more_sync),
                    icon = syncIcon(state.sync.state),
                    iconTint =
                        if (state.sync.state ==
                            SyncState.ERROR
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    supporting =
                        if (state.sync.state == SyncState.SYNCED && last != null) {
                            stringResource(Res.string.more_sync_saved_at, formatTime(last))
                        } else {
                            syncDescription(state.sync)
                        },
                    onClick = onOpenSync,
                )
                ListDivider()
                ListRow(stringResource(Res.string.more_devices), icon = Res.drawable.ic_devices, onClick = navigation.onDevices)
                ListDivider()
                ListRow(
                    stringResource(Res.string.more_help),
                    icon = Res.drawable.ic_help,
                    trailingText = stringResource(Res.string.more_soon),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

private fun syncIcon(state: SyncState): DrawableResource =
    when (state) {
        SyncState.SYNCED -> Res.drawable.ic_cloud_done
        SyncState.OFFLINE -> Res.drawable.ic_cloud_off
        SyncState.ERROR -> Res.drawable.ic_error
        SyncState.PENDING, SyncState.SYNCING -> Res.drawable.ic_cloud_upload
    }

@Composable
private fun ProfileCard(
    state: MainUiState,
    onClick: () -> Unit,
) {
    val administrators = stringResource(Res.string.group_administrators)
    val groups = state.groups.joinToString(" · ") { if (it.builtIn == BuiltInGroup.ADMINISTRATORS) administrators else it.name }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(initialsFor(state.name), state.color, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.name,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontSize =
                            MaterialTheme.typography.titleLarge.fontSize
                                .times(17f / 18f),
                    ),
            )
            if (groups.isNotEmpty()) {
                Text(groups, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppIcon(Res.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class Tile(
    val icon: DrawableResource,
    val title: String,
    val subtitle: String?,
    val enabled: Boolean,
    /** Tiles use palette tones for their icon square, as in the mockup. */
    val tone: PaletteColor,
    val onClick: () -> Unit,
)

@Composable
private fun TileCard(
    tile: Tile,
    modifier: Modifier,
) {
    val tones = tile.tone.tones()
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = tile.enabled, onClick = tile.onClick)
            .padding(Spacing.l)
            .alpha(if (tile.enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(Modifier.size(44.dp).background(tones.container, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            AppIcon(tile.icon, null, tint = tones.onContainer)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tile.title, style = MaterialTheme.typography.titleMedium)
            Text(
                tile.subtitle ?: stringResource(Res.string.placeholder_coming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
