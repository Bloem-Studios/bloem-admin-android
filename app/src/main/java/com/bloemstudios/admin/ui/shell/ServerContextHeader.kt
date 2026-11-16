package com.bloemstudios.admin.ui.shell

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.annotation.DrawableRes
import com.bloemstudios.admin.ui.theme.VeilShapes
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

data class ActivityScopeOption(val id: String, val displayName: String)

/** An organization the signed-in account administers on [serverId], listed under that server. */
data class ScopeOrganization(val serverId: String, val organizationId: String, val displayName: String)

data class ActivityScopeControl(
    val allServers: Boolean,
    val selectedServerId: String?,
    val servers: List<ActivityScopeOption>,
    val onSelectAllServers: () -> Unit,
    val onSelectServer: (String) -> Unit,
    val organizations: List<ScopeOrganization> = emptyList(),
    /** Set while an organization is open; its server's own entry is then not the selected one. */
    val selectedOrganizationId: String? = null,
    val onSelectOrganization: (ScopeOrganization) -> Unit = {},
    /** "Add server" at the foot of the switcher sheet; null leaves the row out. */
    val onAddServer: (() -> Unit)? = null,
    /**
     * The drawer's "Sign out": signs out of what the page shows (the current server, or every saved server on All servers)
     * and removes no server. Null leaves the row out.
     */
    val onSignOut: (() -> Unit)? = null,
)

/**
 * The top bar of the screens that come before a server's pages (checking access, signing in again,
 * choosing an organization): the same row as the signed-in shell, with [title] and the context
 * dropdown and no menu button, as there is no navigation to open. Routing stays with the caller.
 */
@Composable
fun ServerContextHeader(
    context: AdminShellContext,
    enabled: Boolean = true,
    activityScope: ActivityScopeControl? = null,
    title: String? = null,
    onClick: () -> Unit,
) {
    ShellTopBar(
        title = title,
        context = context,
        enabled = enabled,
        activityScope = activityScope,
        onContextClick = onClick,
        onMenuClick = null,
    )
}

/**
 * The web admin's mobile header: a menu button that opens the navigation drawer, the current page
 * title, a search button and the context dropdown. One row; [onMenuClick] is null where the drawer is
 * always visible (wide layouts) or absent, which drops the menu button, and [onSearchClick] is null
 * where there are no pages to search. A null [title] leaves the space empty.
 */
/** The narrowest the server switcher gets in the top bar, so it never disappears. */
private val ContextMinimumWidth = 96.dp

@Composable
internal fun ShellTopBar(
    title: String?,
    context: AdminShellContext?,
    enabled: Boolean,
    activityScope: ActivityScopeControl?,
    onContextClick: () -> Unit,
    onMenuClick: (() -> Unit)?,
    onSearchClick: (() -> Unit)? = null,
    searchEnabled: Boolean = true,
    /** A back arrow in the menu button's place, for pages that sit above the shell. */
    onBack: (() -> Unit)? = null,
) {
    Surface(color = VeilColors.Canvas, contentColor = VeilColors.Text) {
      Column {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = VeilColors.Text),
                    ) {
                        LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(R.string.shell_back))
                    }
                }
                if (onMenuClick != null) {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = VeilColors.Text),
                    ) {
                        LucideIcon(R.drawable.lucide_menu, size = LucideSize.Bar, contentDescription = stringResource(R.string.shell_open_menu))
                    }
                }
                // The page title is measured first, so "Scheduled tasks" or "Organizations" stays whole;
                // search and the server name share what is left, and the server name is the one that
                // shortens. The title never takes the space the icons and a minimum-width server name
                // need, so the server switcher can't shrink to nothing on a narrow bar.
                val rowWidth = this@BoxWithConstraints.maxWidth
                val iconWidth = VeilSpacing.MinimumTouchTarget
                val reserved = VeilSpacing.Tiny * 2 + VeilSpacing.Small * 2 +
                    (if (onBack != null) iconWidth else 0.dp) +
                    (if (onMenuClick != null) iconWidth else 0.dp) +
                    (if (onSearchClick != null) iconWidth else 0.dp) +
                    (if (context != null) minOf(ContextMinimumWidth, rowWidth * 0.35f) else 0.dp)
                val titleMaxWidth = minOf(rowWidth * 0.6f, (rowWidth - reserved).coerceAtLeast(0.dp))
                if (title != null) {
                    Text(
                        text = title,
                        // The page title size, 20/28 (titleLarge).
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = titleMaxWidth)
                            .padding(horizontal = VeilSpacing.Small)
                            .semantics { heading() },
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onSearchClick != null) {
                        IconButton(
                            onClick = onSearchClick,
                            enabled = searchEnabled,
                            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(SearchButtonTag),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = VeilColors.Text,
                                disabledContentColor = VeilColors.Muted,
                            ),
                        ) {
                            LucideIcon(R.drawable.lucide_search, size = LucideSize.Bar, contentDescription = stringResource(R.string.shell_search_open))
                        }
                    }
                    if (context != null) {
                        Box(Modifier.weight(1f, fill = false)) {
                            ServerContextDropdown(context, enabled, activityScope, onContextClick)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = VeilColors.Hairline)
      }
    }
}

/**
 * The shared switcher: the current name followed by a chevron (`Name ⌄`). With a scope control it opens a sheet that lists All
 * servers and each saved server (and the organizations under it), marks the current one, and ends with "Add server";
 * without one it asks the caller to open server management. Disabled when [enabled] is false.
 */
@Composable
internal fun ServerContextDropdown(
    context: AdminShellContext,
    enabled: Boolean,
    activityScope: ActivityScopeControl?,
    onClick: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val description = stringResource(R.string.veil_context_change_description, context.displayName)
    Box {
        TextButton(
            onClick = { if (activityScope != null) choosing = true else onClick() },
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = VeilColors.Text),
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)
                .testTag(ServerSwitcherTag)
                .semantics { contentDescription = description },
        ) {
            Text(context.displayName, style = MaterialTheme.typography.labelLarge, color = VeilColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            LucideIcon(R.drawable.lucide_chevron_down, contentDescription = null, tint = VeilColors.Muted, size = LucideSize.Inline)
        }
    }
    if (activityScope != null && choosing && enabled) {
        ServerSwitcherSheet(activityScope, onDismiss = { choosing = false })
    }
}

/** Test tags of the switcher button and its sheet's rows. */
internal const val ServerSwitcherTag = "shell-switcher"
internal const val ServerSwitcherSheetTag = "shell-switcher-sheet"
internal const val ServerSwitcherAddTag = "shell-switcher-add"
internal const val ServerSwitcherRowTagPrefix = "shell-switcher-row:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSwitcherSheet(scope: ActivityScopeControl, onDismiss: () -> Unit) {
    // The sheet closes first, then the choice is acted on, so a page never changes under an open sheet.
    fun choose(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VeilColors.Raised,
        contentColor = VeilColors.Text,
        modifier = Modifier.testTag(ServerSwitcherSheetTag),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = VeilSpacing.CompactHorizontal)
                .padding(bottom = VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
        ) {
            Text(
                stringResource(R.string.veil_switcher_title),
                style = MaterialTheme.typography.labelMedium,
                color = VeilColors.Muted,
                modifier = Modifier.padding(horizontal = VeilSpacing.Medium, vertical = VeilSpacing.Small).semantics { heading() },
            )
            SwitcherRow(
                label = stringResource(R.string.veil_context_all_servers),
                icon = R.drawable.lucide_network,
                selected = scope.allServers,
                enabled = scope.servers.isNotEmpty(),
                modifier = Modifier.testTag(ServerSwitcherRowTagPrefix + "all"),
                onClick = choose(scope.onSelectAllServers),
            )
            scope.servers.forEach { server ->
                SwitcherRow(
                    label = server.displayName,
                    icon = R.drawable.lucide_server,
                    selected = !scope.allServers && scope.selectedOrganizationId == null && scope.selectedServerId == server.id,
                    modifier = Modifier.testTag(ServerSwitcherRowTagPrefix + server.id),
                    onClick = choose { scope.onSelectServer(server.id) },
                )
                // The web's administrative contexts: each organization this account administers on the server, under it.
                scope.organizations.filter { it.serverId == server.id }.forEach { organization ->
                    SwitcherRow(
                        label = organization.displayName,
                        icon = R.drawable.lucide_building_2,
                        selected = scope.selectedOrganizationId == organization.organizationId && scope.selectedServerId == server.id,
                        indented = true,
                        modifier = Modifier.testTag(ServerSwitcherRowTagPrefix + server.id + ":" + organization.organizationId),
                        onClick = choose { scope.onSelectOrganization(organization) },
                    )
                }
            }
            if (scope.onAddServer != null) {
                HorizontalDivider(Modifier.padding(vertical = VeilSpacing.Small), color = VeilColors.Hairline)
            }
            scope.onAddServer?.let { add ->
                SwitcherRow(
                    label = stringResource(R.string.veil_add_server),
                    icon = R.drawable.lucide_plus,
                    selected = false,
                    modifier = Modifier.testTag(ServerSwitcherAddTag),
                    onClick = choose(add),
                )
            }
        }
    }
}

/** One row of the switcher sheet: 48 tall, an icon in a 24 slot, the name, and a check at the end on the current one. */
@Composable
private fun SwitcherRow(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(VeilShapes.ControlCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (indented) VeilSpacing.Large else 0.dp)
            .clip(shape)
            .background(if (selected) VeilColors.Selected else Color.Transparent, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = VeilSpacing.MinimumTouchTarget)
            .padding(horizontal = VeilSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            LucideIcon(icon, contentDescription = null, tint = if (enabled) VeilColors.Muted else VeilColors.Muted.copy(alpha = 0.5f))
        }
        Spacer(Modifier.width(VeilSpacing.Medium))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) VeilColors.Text else VeilColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            LucideIcon(R.drawable.lucide_check, contentDescription = null, tint = VeilColors.Accent)
        }
    }
}
