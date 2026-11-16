package com.bloemstudios.admin.serverconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.dashboard.formatUptime
import com.bloemstudios.admin.users.detail.formatDateTime
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.Stat
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.time.Instant

const val ServerStatusTag = "settings-status"
const val RestartButtonTag = "settings-restart"

/** The restart reasons as the settings' own names, using the catalogue's labels. Null when no restart is waiting. */
@Composable
internal fun rememberRestartText(status: ServerStatus): String? {
    val context = LocalContext.current
    return remember(status) {
        if (!status.restart_required) null
        else describeRestartReasons(status.allRestartReasons) { key -> labelForKey(key) { context.getString(it.labelRes) } }
    }
}

/**
 * How long the server has been up, a restart it is waiting for (a warning, in the settings' own names, with what a restart
 * costs), and the Restart button.
 */
@Composable
internal fun ServerStatusCard(status: ServerStatus, restartText: String?, restartEnabled: Boolean, onRestart: () -> Unit) {
    val now = remember(status) { Instant.now() }
    RowCard(Modifier.testTag(ServerStatusTag)) {
        Stat(
            label = stringResource(R.string.dashboard_health_uptime),
            value = formatUptime(status.started_at, now),
            detail = formatDateTime(status.started_at)?.let { stringResource(R.string.settings_status_started, it) },
        )
        if (status.restart_required) {
            StatusLabel(
                if (restartText != null) stringResource(R.string.settings_status_restart_needed, restartText)
                else stringResource(R.string.server_config_restart_required_no_reason),
                tone = StatusTone.Warning, dot = true, maxLines = 4,
            )
            Text(stringResource(R.string.settings_status_restart_consequence), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        Row(Modifier.padding(top = VeilSpacing.Small)) {
            OutlinedButton(enabled = restartEnabled, onClick = onRestart, modifier = Modifier.testTag(RestartButtonTag)) { Text(stringResource(R.string.server_config_restart)) }
        }
    }
}

/** One stored setting by its key, with its value, a restart marker where it applies, and an Edit button. */
@Composable
internal fun SettingRow(setting: AdminSetting, editEnabled: Boolean, restartOnly: Boolean = false, onEdit: () -> Unit) {
    RowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f)) {
                Text(setting.key, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                Text(setting.value, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                if (restartOnly) StatusLabel(stringResource(R.string.settings_restart_marker), tone = StatusTone.Warning)
            }
            TextButton(enabled = editEnabled, onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
        }
    }
}
