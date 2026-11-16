package com.bloemstudios.admin.users

import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch

/**
 * Sessions and devices of one user, shown on the User page's Sign-in tab. A fresh UserSessionsViewModel
 * is remembered per user.id (not the nav-backstack-scoped viewModel() factory), so opening another user
 * never shows stale data from the previous one. [canRevoke] is false for an account the admin may only
 * view; revoking is then not offered at all.
 */
@Composable
fun UserSessionsPanel(
    user: AdminUser,
    client: ApiClient,
    canRevoke: Boolean = true,
    showSessions: Boolean = true,
    showDevices: Boolean = true
) {
    val viewModel = remember(client, user.id) { UserSessionsViewModel() }
    val sessions by viewModel.sessions.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val sessionsLoading by viewModel.sessionsLoading.collectAsState()
    val devicesLoading by viewModel.devicesLoading.collectAsState()
    val sessionsLoaded by viewModel.sessionsLoaded.collectAsState()
    val devicesLoaded by viewModel.devicesLoaded.collectAsState()
    val sessionsError by viewModel.sessionsError.collectAsState()
    val devicesError by viewModel.devicesError.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val haptic = LocalHapticFeedback.current

    var sessionPendingRevoke by remember { mutableStateOf<LoginSession?>(null) }
    var showingRevokeAllConfirm by remember { mutableStateOf(false) }
    // Separate from section errors: an action failure (revoke) must not be clobbered by, or clobber, the
    // load-failure banner -- same explicit-result-signal reasoning as UsersViewModel's create/update actions.
    var actionError by remember { mutableStateOf<ErrorText?>(null) }
    var revokedCount by remember { mutableStateOf<Int?>(null) }

    ForegroundOnceEffect(client, user.id, showSessions, showDevices) {
        if (showSessions) launch { if (!viewModel.sessionsLoaded.value) viewModel.loadSessions(user.id, client) }
        if (showDevices) launch { if (!viewModel.devicesLoaded.value) viewModel.loadDevices(user.id, client) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        actionError?.let {
            Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { actionError = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
        revokedCount?.let { count ->
            Text(pluralStringResource(R.plurals.user_sessions_revoked_count, count, count))
        }

        if (showSessions) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.user_sessions_section_title), style = MaterialTheme.typography.titleMedium)
                if (canRevoke && sessions.isNotEmpty()) {
                    TextButton(onClick = { showingRevokeAllConfirm = true }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.user_sessions_revoke_all))
                    }
                }
            }
            if (sessionsLoading) Text(stringResource(R.string.management_loading))
            sessionsError?.let {
                Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    enabled = !sessionsLoading,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { reads.launch { viewModel.loadSessions(user.id, client) } }
                ) { Text(stringResource(R.string.action_retry)) }
            }
            if (sessionsLoaded && !sessionsLoading && sessionsError == null && sessions.isEmpty()) {
                Text(stringResource(R.string.user_sessions_none))
            } else {
                sessions.forEach { session ->
                    ListRowCard(
                        title = session.device_name.ifEmpty { stringResource(R.string.user_sessions_unknown_device) },
                        supporting = listOfNotNull(
                            listOfNotNull(
                                session.ip_address.ifEmpty { null },
                                if (session.current) stringResource(R.string.user_sessions_current) else null
                            )
                                .joinToString(" · ")
                                .ifEmpty { session.id },
                            formatDateTime(session.last_seen_at)?.let { stringResource(R.string.user_session_last_seen, it) },
                            formatDateTime(session.expires_at)?.let { stringResource(R.string.user_session_expires, it) },
                        ),
                        trailing = if (canRevoke) {
                            {
                                TextButton(onClick = { sessionPendingRevoke = session }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                                    Text(stringResource(R.string.action_revoke))
                                }
                            }
                        } else null,
                    )
                }
            }
        }

        if (showDevices) {
            Text(
                stringResource(R.string.user_devices_section_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = VeilSpacing.Medium)
            )
            if (devicesLoading) Text(stringResource(R.string.management_loading))
            devicesError?.let {
                Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    enabled = !devicesLoading,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { reads.launch { viewModel.loadDevices(user.id, client) } }
                ) { Text(stringResource(R.string.action_retry)) }
            }
            if (devicesLoaded && !devicesLoading && devicesError == null && devices.isEmpty()) {
                Text(stringResource(R.string.user_devices_none))
            } else {
                devices.forEach { device ->
                    ListRowCard(
                        title = device.device_name,
                        supporting = listOf(
                            device.device_platform,
                            formatDateTime(device.last_seen_at)?.let { stringResource(R.string.user_device_last_seen, it) }
                                ?: stringResource(R.string.user_device_never_seen),
                        ),
                    )
                }
            }
        }
    }

    sessionPendingRevoke?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingRevoke = null },
            title = { Text(stringResource(R.string.user_sessions_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.user_sessions_revoke_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = session
                    sessionPendingRevoke = null
                    scope.launch {
                        when (val result = viewModel.revokeSession(user.id, target.id, client)) {
                            is RevokeResult.Success -> {
                                actionError = null
                                revokedCount = null
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            is RevokeResult.Failure -> actionError = result.message
                        }
                    }
                }) { Text(stringResource(R.string.action_revoke)) }
            },
            dismissButton = { TextButton(onClick = { sessionPendingRevoke = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showingRevokeAllConfirm) {
        AlertDialog(
            onDismissRequest = { showingRevokeAllConfirm = false },
            title = { Text(stringResource(R.string.user_sessions_revoke_all_confirm_title)) },
            // If the user being viewed is the admin themself, this WILL log the admin out of their own
            // session too. The warning calls that out unconditionally rather than guessing who "me" is.
            text = { Text(stringResource(R.string.user_sessions_revoke_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showingRevokeAllConfirm = false
                    scope.launch {
                        when (val result = viewModel.revokeAllSessions(user.id, client)) {
                            is RevokeResult.Success -> {
                                actionError = null
                                revokedCount = result.revoked
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            is RevokeResult.Failure -> actionError = result.message
                        }
                    }
                }) { Text(stringResource(R.string.user_sessions_revoke_all)) }
            },
            dismissButton = { TextButton(onClick = { showingRevokeAllConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
