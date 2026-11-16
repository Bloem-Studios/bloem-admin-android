package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import com.bloemstudios.admin.ui.roleLabelRes
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.ui.platform.testTag
import com.bloemstudios.admin.ui.gestures.horizontalSwipe
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.shell.LocalShellDrawerOpen
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.PasswordResetDialog
import kotlinx.coroutines.launch

private enum class UserTab { Overview, Profiles, Access, SignIn, Activity, Downloads, Preferences }

/**
 * The User page: a page for one account, reached by tapping its row in a Users list. The Users destination
 * shows it in place of the list, inside the shell, so the top bar, drawer and context dropdown stay usable.
 * Closing it (the back arrow or system back) returns to the list, which keeps its search and scroll position.
 * Works the same from the single-server list and from All servers, where [UserAdmin.serverName] names
 * the server the account lives on.
 */
/** Test tag of the area that answers tab swipes. */
const val UserPageSwipeTag = "user-page-swipe"

@Composable
internal fun UserDetailScreen(admin: UserAdmin, userId: String, onClose: () -> Unit) {
    val viewModel = remember(admin, userId) { UserDetailViewModel(admin, userId) }
    ForegroundOnceEffect(viewModel) { viewModel.open() }
    // The page's flows are read here, as plain values, so this function recomposes when one changes.
    val state = viewModel.state.collectAsState().value
    val viewer = viewModel.viewer.collectAsState().value
    val capabilities = viewModel.capabilities.collectAsState().value
    val busy = viewModel.busy.collectAsState().value
    val actionError = admin.actionError.collectAsState().value
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showSetPassword by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val close = {
        viewModel.dismissActionError()
        onClose()
    }
    val requestClose = { if (dirty) confirmDiscard = true else close() }

    // The page fills the Users destination, under the shell's top bar and beside its drawer. Back asks first when
    // there are unsaved edits, then returns to the list. An open drawer takes back first, as it does elsewhere.
    BackHandler(enabled = !LocalShellDrawerOpen.current, onBack = requestClose)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Small, vertical = VeilSpacing.Tiny),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = requestClose, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(R.string.user_page_back))
            }
            Text(
                (state as? UserPageState.Ready)?.user?.username ?: stringResource(R.string.user_page_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
        }

        when (val page = state) {
            UserPageState.Loading -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Loading,
                title = "",
                message = "",
                stateDescription = stringResource(R.string.user_page_loading),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal)
            )
            is UserPageState.Failed -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Failure,
                title = stringResource(R.string.management_failed_title),
                message = page.message.resolve(),
                stateDescription = page.message.resolve(),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
                action = VeilStateSurfaceAction(stringResource(R.string.action_retry)) { scope.launch { viewModel.open() } }
            )
            UserPageState.Gone -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Empty,
                title = stringResource(R.string.user_page_gone_title),
                message = stringResource(R.string.user_page_gone_body),
                stateDescription = stringResource(R.string.user_page_gone_title),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
                action = VeilStateSurfaceAction(stringResource(R.string.user_page_back), close)
            )
            is UserPageState.Ready -> {
                val user = page.user
                val actions = userActions(user, viewer)
                val tabs = buildList {
                    add(UserTab.Overview)
                    // Platform mode only, and only where the server lets an admin manage profiles.
                    if (capabilities?.profile_management == true) add(UserTab.Profiles)
                    add(UserTab.Access)
                    add(UserTab.SignIn)
                    add(UserTab.Activity)
                    if (capabilities?.account_downloads == true) add(UserTab.Downloads)
                    add(UserTab.Preferences)
                }
                val selected = tabIndex.coerceIn(0, tabs.lastIndex)

                Column(
                    Modifier.weight(1f)
                        // A sideways swipe changes this user's tab and never leaves the page.
                        .testTag(UserPageSwipeTag)
                        .horizontalSwipe(tabs, selected) { step -> (selected + step).takeIf { it in tabs.indices }?.let { tabIndex = it } }
                        .verticalScroll(rememberScrollState()).padding(horizontal = VeilSpacing.CompactHorizontal),
                    verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)
                ) {
                    actionError?.let {
                        Text(it.resolve(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::dismissActionError, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                    notice?.let { Text(it, color = VeilColors.Ok) }
                    if (actions.manageable && page.validator == null) {
                        Text(stringResource(R.string.user_no_version_note), color = VeilColors.Warning)
                    }
                    UserHeader(
                        user = user,
                        actions = actions,
                        serverName = admin.serverName,
                        busy = busy,
                        onReset = { showReset = true },
                        onSetPassword = { showSetPassword = true },
                        onDisable = { confirmDisable = true },
                        onEnable = { scope.launch { viewModel.setEnabled(true) } },
                        onDelete = { confirmDelete = true }
                    )

                    ScrollableTabRow(
                        selectedTabIndex = selected, edgePadding = 0.dp,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = VeilColors.Text,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == selected,
                                onClick = { tabIndex = index },
                                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                                selectedContentColor = VeilColors.Text,
                                unselectedContentColor = VeilColors.Muted,
                                text = { Text(stringResource(tab.labelRes())) }
                            )
                        }
                    }

                    when (tabs[selected]) {
                        UserTab.Overview -> UserOverviewTab(user, admin.client, admin.serverName, capabilities)
                        UserTab.Profiles -> UserProfilesTab(user, admin, manageable = actions.manageable)
                        UserTab.Access -> {
                            // The groups, libraries and defaults the form chooses from are read when the tab is first shown.
                            ForegroundOnceEffect(viewModel) { viewModel.loadAccessChoices() }
                            UserAccessTab(
                                user = user,
                                validator = page.validator,
                                editable = actions.policyManageable,
                                policyOwnerOnly = actions.manageable && !actions.policyManageable,
                                choices = AccessChoices(
                                    viewModel.groups.collectAsState().value,
                                    viewModel.libraries.collectAsState().value,
                                    viewModel.policyDefaults.collectAsState().value
                                ),
                                onRetryChoices = { scope.launch { viewModel.loadAccessChoices() } },
                                onDirtyChange = { dirty = it },
                                onSave = { viewModel.saveAccess(it) }
                            )
                        }
                        UserTab.Preferences -> UserPreferencesTab(
                            user = user,
                            admin = admin,
                            devicesAvailable = capabilities?.account_devices == true,
                            editable = actions.manageable
                        )
                        UserTab.SignIn -> UserSignInTab(user, admin.client, capabilities, canRevoke = actions.manageable)
                        UserTab.Activity -> UserActivityTab(user, admin.client, admin.serverName)
                        UserTab.Downloads -> UserDownloadsTab(user, admin.client, admin.serverName)
                    }
                }

                if (showReset) PasswordResetDialog(admin.client, user, capabilities) { showReset = false }

                if (showSetPassword) {
                    val done = stringResource(R.string.user_setpw_done, user.username)
                    val doneTemporary = stringResource(R.string.user_setpw_done_temporary, user.username)
                    SetPasswordDialog(
                        user = user,
                        onSubmit = { password, requireChange -> viewModel.setPassword(password, requireChange) },
                        onDone = { requireChange ->
                            showSetPassword = false
                            notice = if (requireChange) doneTemporary else done
                        },
                        onDismiss = { showSetPassword = false }
                    )
                }

                if (confirmDisable) {
                    AlertDialog(
                        onDismissRequest = { confirmDisable = false },
                        title = { Text(stringResource(R.string.user_disable_confirm_title, user.username)) },
                        text = { Text(stringResource(R.string.user_disable_confirm_body)) },
                        confirmButton = {
                            TextButton(
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                                onClick = {
                                    confirmDisable = false
                                    scope.launch { viewModel.setEnabled(false) }
                                }
                            ) { Text(stringResource(R.string.user_action_disable)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDisable = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.user_delete_confirm_title, user.username)) },
                        text = { Text(stringResource(R.string.user_delete_confirm_body)) },
                        confirmButton = {
                            DestructiveTextButton(
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                                onClick = {
                                    confirmDelete = false
                                    scope.launch {
                                        if (viewModel.delete() == ActionResult.Success) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onClose()
                                        }
                                    }
                                }
                            ) { Text(stringResource(R.string.user_action_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.user_discard_title)) },
            text = { Text(stringResource(R.string.user_discard_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { confirmDiscard = false; close() }
                ) { Text(stringResource(R.string.user_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.user_discard_keep))
                }
            }
        )
    }
}

private fun UserTab.labelRes(): Int = when (this) {
    UserTab.Overview -> R.string.user_tab_overview
    UserTab.Profiles -> R.string.user_tab_profiles
    UserTab.Access -> R.string.user_tab_access
    UserTab.Preferences -> R.string.user_tab_preferences
    UserTab.SignIn -> R.string.user_tab_sign_in
    UserTab.Activity -> R.string.user_tab_activity
    UserTab.Downloads -> R.string.user_tab_downloads
}

/** Name, role, status, sign-in method and the web's actions; actions the admin cannot take are not drawn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserHeader(
    user: AdminUser,
    actions: UserActions,
    serverName: String?,
    busy: Boolean,
    onReset: () -> Unit,
    onSetPassword: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onDelete: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            StatusLabel(codeLabel(user.roleLabel(), ::roleLabelRes))
            if (user.enabled) StatusLabel(stringResource(R.string.user_status_active))
            else StatusLabel(stringResource(R.string.user_status_disabled), tone = StatusTone.Warning, dot = true)
            StatusLabel(stringResource(if (user.password_login) R.string.user_sign_in_password else R.string.user_sign_in_external))
            if (user.password_change_required) StatusLabel(stringResource(R.string.user_badge_temporary_password), tone = StatusTone.Warning, dot = true)
        }
        serverName?.let { Text(stringResource(R.string.users_server_label, it), color = VeilColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        if (user.email.isNotBlank()) Text(user.email, color = VeilColors.Muted, style = MaterialTheme.typography.bodyMedium)
        formatDate(user.created_at)?.let { Text(stringResource(R.string.user_meta_joined, it), color = VeilColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        Text(
            formatDate(user.last_active_at)?.let { stringResource(R.string.user_meta_last_active, it) }
                ?: stringResource(R.string.user_meta_no_activity),
            color = VeilColors.Muted, style = MaterialTheme.typography.bodyMedium
        )
        when (actions.viewOnly) {
            ViewOnlyReason.Owner -> Text(stringResource(R.string.user_view_only_owner), color = VeilColors.Muted)
            ViewOnlyReason.AnotherAdmin -> Text(stringResource(R.string.user_view_only_admin), color = VeilColors.Muted)
            null -> Unit
        }
        if (!user.enabled) Text(stringResource(R.string.user_disabled_note), color = VeilColors.Warning)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            if (actions.canResetPassword) ActionButton(R.string.user_action_reset_password, !busy, onReset)
            if (actions.canSetPassword) ActionButton(R.string.user_action_set_temporary_password, !busy, onSetPassword)
            if (actions.canEnable) ActionButton(R.string.user_action_enable, !busy, onEnable)
            if (actions.canDisable) ActionButton(R.string.user_action_disable, !busy, onDisable)
            if (actions.canDelete) ActionButton(R.string.user_action_delete, !busy, onDelete, destructive = true)
        }
    }
}

@Composable
private fun ActionButton(label: Int, enabled: Boolean, onClick: () -> Unit, destructive: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) VeilColors.Danger else VeilColors.Text)
    ) { Text(stringResource(label)) }
}
