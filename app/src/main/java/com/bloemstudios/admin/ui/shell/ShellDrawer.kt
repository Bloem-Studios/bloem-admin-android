package com.bloemstudios.admin.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.annotation.DrawableRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

// Test tags. Prefixes are constants so instrumented tests can build tags without calling into the
// (minified) app code.
internal const val DrawerDestinationTagPrefix = "shell-destination:"
internal const val DrawerGroupTagPrefix = "shell-group:"
internal fun drawerDestinationTag(id: String) = DrawerDestinationTagPrefix + id
internal fun drawerGroupTag(group: AdminGroup) = DrawerGroupTagPrefix + group.name
internal const val DrawerBadgeTag = "shell-destination-badge"
internal const val DrawerManageServersTag = "shell-manage-servers"
internal const val DrawerSettingsTag = "shell-settings"
internal const val DrawerSignOutTag = "shell-sign-out"

/**
 * What the navigation drawer lists: the destination groups the server's web admin uses, then the foot, in this order: Manage servers, Settings and Sign out, and nothing else.
 *
 * Every item is disabled while [locked], so a destination cannot change under an open dialog.
 * [onSettings] and [onSignOut] are null where the host provides none; the row is then left out.
 */
@Composable
internal fun ShellDrawerContent(
    destinations: List<AdminDestination>,
    selectedDestinationId: String?,
    locked: Boolean,
    manageServersEnabled: Boolean,
    onDestination: (AdminDestination) -> Unit,
    onManageServers: () -> Unit,
    onSettings: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** False while the page has asked for no change of server or sign-in (the same switch that disables the switcher). */
    signOutEnabled: Boolean = true,
    /** A count shown at the end of a destination's row, by destination id; a missing or zero entry shows none. */
    badges: Map<String, Int> = emptyMap(),
) {
    Column(modifier = modifier.padding(VeilSpacing.Small)) {
        // Destinations scroll; the app's own items stay pinned below them.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
            groupedForDrawer(destinations).forEachIndexed { groupIndex, (group, items) ->
                // A group label sits closer to its own rows (8) than to the group above it (24).
                Text(
                    text = stringResource(group.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = VeilColors.Muted,
                    modifier = Modifier
                        .padding(
                            start = VeilSpacing.CompactHorizontal,
                            top = if (groupIndex == 0) VeilSpacing.Small else VeilSpacing.Large,
                            bottom = VeilSpacing.Small,
                        )
                        .testTag(drawerGroupTag(group))
                        .semantics { heading() },
                )
                items.forEach { destination ->
                    DrawerEntry(
                        label = stringResource(destination.labelRes),
                        icon = destination.icon,
                        selected = destination.id == selectedDestinationId,
                        enabled = !locked,
                        modifier = Modifier.testTag(drawerDestinationTag(destination.id)),
                        badge = badges[destination.id]?.takeIf { it > 0 },
                        onClick = { onDestination(destination) },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = VeilSpacing.Small), color = VeilColors.Hairline)
        DrawerEntry(
            label = stringResource(R.string.veil_manage_servers),
            icon = R.drawable.lucide_server,
            selected = false,
            enabled = !locked && manageServersEnabled,
            modifier = Modifier.testTag(DrawerManageServersTag),
            onClick = onManageServers,
        )
        if (onSettings != null) {
            DrawerEntry(
                label = stringResource(R.string.app_settings_title),
                icon = R.drawable.lucide_settings,
                selected = false,
                enabled = !locked,
                modifier = Modifier.testTag(DrawerSettingsTag),
                onClick = onSettings,
            )
        }
        if (onSignOut != null) {
            DrawerEntry(
                label = stringResource(R.string.drawer_sign_out),
                icon = R.drawable.lucide_log_out,
                selected = false,
                enabled = !locked && signOutEnabled,
                modifier = Modifier.testTag(DrawerSignOutTag),
                onClick = onSignOut,
            )
        }
    }
}

@Composable
private fun DrawerEntry(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val labelColor = if (enabled) VeilColors.Text else VeilColors.Muted
    // The current page: the selected surface and an accent icon, nothing more.
    val iconColor = if (selected && enabled) VeilColors.Accent else VeilColors.Muted
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(VeilShapes.ControlCorner),
        color = if (selected) VeilColors.Selected else Color.Transparent,
        contentColor = labelColor,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Tab },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = VeilSpacing.MinimumTouchTarget)
                .padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One 24dp slot holds every icon, so the labels start on one line.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                LucideIcon(icon, contentDescription = null, tint = iconColor, size = LucideSize.Row)
            }
            Spacer(Modifier.width(VeilSpacing.Medium))
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                // A count is quiet text, not a pill and not a color: the orange dot is for live things only.
                val description = pluralStringResource(R.plurals.requests_waiting_description, badge, badge)
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = VeilColors.Muted,
                    modifier = Modifier
                        .padding(start = VeilSpacing.Small)
                        .testTag(DrawerBadgeTag)
                        .semantics { contentDescription = description },
                )
            }
        }
    }
}
