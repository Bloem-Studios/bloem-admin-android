package com.bloemstudios.admin.users.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.UserSessionsPanel

/** Sign-in: how the account signs in, then its sessions and devices with Revoke and Revoke all sessions. */
@Composable
internal fun UserSignInTab(
    user: AdminUser,
    client: ApiClient,
    capabilities: AdminUserCapabilities?,
    canRevoke: Boolean
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
        DetailCard(stringResource(R.string.user_signin_password_title)) {
            Text(stringResource(if (user.password_login) R.string.user_sign_in_password else R.string.user_sign_in_external))
            Text(
                stringResource(if (user.password_login) R.string.user_signin_password_on else R.string.user_signin_password_off),
                color = VeilColors.Muted
            )
            if (user.password_change_required) Text(stringResource(R.string.user_signin_temporary), color = VeilColors.Warning)
        }
        // An unknown capability (the read failed) keeps the section, as the dialog always did.
        val sessions = capabilities?.login_sessions != false
        val devices = capabilities?.account_devices != false
        DetailCard(stringResource(R.string.user_signin_sessions_title)) {
            if (!sessions) Text(stringResource(R.string.user_signin_sessions_unavailable), color = VeilColors.Muted)
            if (!devices) Text(stringResource(R.string.user_signin_devices_unavailable), color = VeilColors.Muted)
            if (sessions || devices) {
                UserSessionsPanel(user, client, canRevoke = canRevoke, showSessions = sessions, showDevices = devices)
            }
        }
    }
}
