package com.bloemstudios.admin.servers

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.VeilDialog
import com.bloemstudios.admin.ui.components.LucideSize

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.shell.ShellTopBar
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

// Test tags. Constants, so instrumented tests can build tags without calling into the (minified) app.
internal const val ServerRowTagPrefix = "server-row:"
internal const val ServerActionsTagPrefix = "server-actions:"
internal const val ServerSignInStateTagPrefix = "server-sign-in:"
internal const val ServerListAllServersTag = "server-list-all-servers"
internal const val ServerListAddServerTag = "server-list-add-server"
internal const val ServerListAppSettingsTag = "server-list-app-settings"

/** A row's place in a settings-style group: the first and last rows carry the group's rounded corners. */
@Suppress("UNUSED_PARAMETER")
private fun groupShape(index: Int, count: Int) = RoundedCornerShape(VeilShapes.CompactCardCorner)

/**
 * Manage servers, laid out like one of the web admin's settings pages: a title, then grouped lists --
 * All servers, the saved servers (each with its address and whether this device is signed in to it,
 * and Rename, Forget credentials and Remove in its menu) and the app's own settings. Add Server stays
 * at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    store: ServerStore,
    onServerSelected: (AdminServer) -> Unit,
    onAppSettingsRequested: () -> Unit = {},
    onAllServersSelected: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val servers by store.servers.collectAsState()
    val recoveredCredentials by store.recoveredCredentials.collectAsState()
    val credentialStorageAvailable by store.credentialStorageAvailable.collectAsState()
    val credentialRevision by store.credentialRevision.collectAsState()
    var showingAddServer by remember { mutableStateOf(false) }
    var serverBeingRenamed by remember { mutableStateOf<AdminServer?>(null) }
    var serverPendingCredentialForget by remember { mutableStateOf<AdminServer?>(null) }
    var serverPendingRemoval by remember { mutableStateOf<AdminServer?>(null) }
    var showingRecoveryExplanation by remember(recoveredCredentials) { mutableStateOf(recoveredCredentials) }
    val scope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = VeilColors.Canvas,
        topBar = {
            // The same bar as every other screen: a back arrow where there is somewhere to go back to,
            // and the page title.
            ShellTopBar(
                title = stringResource(R.string.server_list_title),
                context = null,
                enabled = true,
                activityScope = null,
                onContextClick = {},
                onMenuClick = null,
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(color = VeilColors.Card, contentColor = VeilColors.Text) {
                Button(
                    onClick = { showingAddServer = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(VeilSpacing.CompactHorizontal)
                        .heightIn(min = VeilSpacing.MinimumTouchTarget)
                        .testTag(ServerListAddServerTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VeilColors.Accent,
                        contentColor = VeilColors.OnAccent,
                    ),
                ) {
                    LucideIcon(R.drawable.lucide_plus, contentDescription = null, size = LucideSize.Inline)
                    Spacer(Modifier.width(VeilSpacing.Small))
                    Text(
                        text = stringResource(R.string.server_list_add_server_description),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            item {
                Text(
                    text = stringResource(R.string.server_list_guidance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeilColors.Muted,
                    modifier = Modifier.padding(bottom = VeilSpacing.Small),
                )
            }
            item {
                SettingsRow(
                    shape = groupShape(0, 1),
                    onClick = onAllServersSelected,
                    modifier = Modifier.testTag(ServerListAllServersTag),
                ) {
                    // Every row starts with a 24dp slot, so the names line up.
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        LucideIcon(R.drawable.lucide_server, contentDescription = null, tint = VeilColors.Muted)
                    }
                    Spacer(Modifier.width(VeilSpacing.Medium))
                    Column(Modifier.weight(1f).padding(vertical = VeilSpacing.Medium)) {
                        Text(stringResource(R.string.server_list_all_servers), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.server_list_all_servers_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = VeilColors.Muted,
                            modifier = Modifier.padding(top = VeilSpacing.Tiny),
                        )
                    }
                    LucideIcon(R.drawable.lucide_chevron_right, contentDescription = null, tint = VeilColors.Muted)
                }
            }
            item {
                SectionLabel(stringResource(R.string.server_list_saved_eyebrow))
            }
            if (servers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.server_list_no_saved_servers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VeilColors.Muted,
                    )
                }
            }
            itemsIndexed(servers, key = { _, server -> server.id }) { index, server ->
                var showingActions by remember(server.id) { mutableStateOf(false) }
                val connectionProblem = server.connectionProblem
                // Read again whenever any sign-in changes, so the state here is never stale.
                val signedIn = remember(server.id, credentialRevision) { store.accessToken(server.id) != null }
                SettingsRow(
                    shape = groupShape(index, servers.size),
                    onClick = if (connectionProblem == null) ({ onServerSelected(server) }) else null,
                    modifier = Modifier.testTag(ServerRowTagPrefix + server.id),
                ) {
                    // A 24dp slot with an 8dp dot: quiet when all is well, warning when signed out, danger when the address is refused.
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        connectionProblem != null -> VeilColors.Danger
                                        signedIn -> VeilColors.Muted
                                        else -> VeilColors.Warning
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.width(VeilSpacing.Medium))
                    Column(Modifier.weight(1f).padding(vertical = VeilSpacing.Medium)) {
                        Text(
                            text = server.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = server.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = VeilColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = VeilSpacing.Tiny),
                        )
                        if (connectionProblem != null) {
                            Text(
                                text = stringResource(R.string.server_list_https_required),
                                style = MaterialTheme.typography.labelSmall,
                                color = VeilColors.Danger,
                                modifier = Modifier.padding(top = VeilSpacing.Small),
                            )
                        } else {
                            // Said once, as quiet text; only Signed out takes a color.
                            Text(
                                text = stringResource(
                                    if (signedIn) R.string.server_list_signed_in else R.string.server_list_signed_out,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (signedIn) VeilColors.Muted else VeilColors.Warning,
                                modifier = Modifier.padding(top = VeilSpacing.Small).testTag(ServerSignInStateTagPrefix + server.id),
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showingActions = true },
                            modifier = Modifier.size(VeilSpacing.MinimumTouchTarget).testTag(ServerActionsTagPrefix + server.id),
                        ) {
                            LucideIcon(R.drawable.lucide_ellipsis,
                                contentDescription = stringResource(R.string.server_list_actions_description, server.displayName),
                            )
                        }
                        DropdownMenu(expanded = showingActions, onDismissRequest = { showingActions = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_list_rename)) },
                                onClick = {
                                    showingActions = false
                                    serverBeingRenamed = server
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_list_forget_credentials)) },
                                enabled = signedIn,
                                onClick = {
                                    showingActions = false
                                    serverPendingCredentialForget = server
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_list_remove)) },
                                onClick = {
                                    showingActions = false
                                    serverPendingRemoval = server
                                }
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel(stringResource(R.string.server_list_app_section))
            }
            item {
                SettingsRow(
                    shape = groupShape(0, 1),
                    onClick = onAppSettingsRequested,
                    modifier = Modifier.testTag(ServerListAppSettingsTag),
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        LucideIcon(R.drawable.lucide_settings, contentDescription = null, tint = VeilColors.Muted)
                    }
                    Spacer(Modifier.width(VeilSpacing.Medium))
                    Column(Modifier.weight(1f).padding(vertical = VeilSpacing.Medium)) {
                        Text(stringResource(R.string.app_settings_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.server_list_app_settings_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = VeilColors.Muted,
                            modifier = Modifier.padding(top = VeilSpacing.Tiny),
                        )
                    }
                    LucideIcon(R.drawable.lucide_chevron_right, contentDescription = null, tint = VeilColors.Muted)
                }
            }
        }
    }

    if (showingAddServer) {
        AddServerScreen(store = store, onDismiss = { showingAddServer = false })
    }

    serverBeingRenamed?.let { server ->
        RenameServerDialog(
            server = server,
            onDismiss = { serverBeingRenamed = null },
            onRename = { newName ->
                scope.launch { store.renameServer(server.id, newName) }
                serverBeingRenamed = null
            }
        )
    }

    serverPendingCredentialForget?.let { server ->
        AlertDialog(
            onDismissRequest = { serverPendingCredentialForget = null },
            title = {
                Text(stringResource(R.string.server_list_forget_credentials_confirm_title, server.displayName))
            },
            text = { Text(stringResource(R.string.server_list_forget_credentials_confirm_body)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    // Pushes stop first, by the device's own token and within a bounded wait, so a server that does not answer cannot hang this.
                    scope.launch { com.bloemstudios.admin.push.PushRuntime.unregister(appContext, server); store.forgetCredentials(server.id) }
                    serverPendingCredentialForget = null
                }) { Text(stringResource(R.string.server_list_forget_credentials)) }
            },
            dismissButton = {
                TextButton(onClick = { serverPendingCredentialForget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Destructive -- removes the server entry and its stored tokens outright
    // (see ServerStore.removeServer), unlike Forget credentials above which
    // keeps the server metadata. Confirmation-gated for that reason.
    serverPendingRemoval?.let { server ->
        AlertDialog(
            onDismissRequest = { serverPendingRemoval = null },
            title = { Text(stringResource(R.string.server_list_remove_confirm_title, server.displayName)) },
            text = { Text(stringResource(R.string.server_list_remove_confirm_body)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    scope.launch { com.bloemstudios.admin.push.PushRuntime.unregister(appContext, server); store.removeServer(server.id) }
                    serverPendingRemoval = null
                }) { Text(stringResource(R.string.server_list_remove)) }
            },
            dismissButton = { TextButton(onClick = { serverPendingRemoval = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (!credentialStorageAvailable) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.credential_storage_unavailable_title)) },
            text = { Text(stringResource(R.string.credential_storage_unavailable_body)) },
            confirmButton = {}
        )
    } else if (showingRecoveryExplanation) {
        AlertDialog(
            onDismissRequest = { showingRecoveryExplanation = false },
            title = { Text(stringResource(R.string.credentials_recovered_title)) },
            text = { Text(stringResource(R.string.credentials_recovered_body)) },
            confirmButton = {
                TextButton(onClick = { showingRecoveryExplanation = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun RenameServerDialog(server: AdminServer, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var displayName by remember { mutableStateOf(server.displayName) }

    VeilDialog(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.server_list_rename_dialog_title, server.displayName),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.server_list_display_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                enabled = displayName.isNotBlank(),
                onClick = { onRename(displayName.trim()) },
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = VeilColors.Accent, contentColor = VeilColors.OnAccent),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = VeilColors.Muted,
        modifier = Modifier
            .padding(top = VeilSpacing.Large, bottom = VeilSpacing.Small)
            .semantics { heading() },
    )
}

/** One row of a settings group: at least 48dp tall, tappable as a whole when [onClick] is given. */
@Composable
private fun SettingsRow(
    shape: RoundedCornerShape,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = VeilColors.Card,
        contentColor = VeilColors.Text,
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.dp, VeilColors.Hairline),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VeilSpacing.MinimumTouchTarget)
                .padding(start = VeilSpacing.CompactHorizontal, end = VeilSpacing.Tiny),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
