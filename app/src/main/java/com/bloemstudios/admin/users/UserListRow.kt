package com.bloemstudios.admin.users

import com.bloemstudios.admin.ui.roleLabelRes
import com.bloemstudios.admin.ui.codeLabel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.users.detail.roleLabel

/** A Users list row: name, email, role and status. Every row has the status as a dot and a word; only a disabled account is colored. */
@Composable
internal fun UserListRow(user: AdminUser, enabled: Boolean = true, pending: Boolean = false, onOpen: () -> Unit) {
    val role = codeLabel(user.roleLabel(), ::roleLabelRes)
    val status = stringResource(if (user.enabled) R.string.user_status_active else R.string.user_status_disabled)
    val description = stringResource(R.string.users_row_description, user.username, role, status)
    ListRowCard(
        title = user.username,
        supporting = listOf(user.email, role),
        onClick = onOpen,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
        // Status is a dot and a word on every row: quiet for Active, a warning dot for Disabled or a change under way.
        trailing = {
            if (pending) StatusLabel(stringResource(R.string.action_in_progress), tone = StatusTone.Warning, dot = true)
            else StatusLabel(status, tone = if (user.enabled) StatusTone.Quiet else StatusTone.Warning, dot = true)
        },
    )
}
