package com.bloemstudios.admin.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bloemstudios.admin.R
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

const val AlertMasterTag = "alerts-master"
const val AlertDetailsTag = "alerts-details"
const val AlertServerTagPrefix = "alerts-server:"
const val AlertSystemSettingsTag = "alerts-open-system-settings"

/**
 * The "Server alerts" part of App settings, with its state held by the caller: a master switch, a switch per saved
 * server, and whether the lock screen may show details. Everything is off until the admin turns it on.
 */
@Composable
fun AlertSettingsSection(
    enabled: Boolean,
    showDetailsOnLockScreen: Boolean,
    servers: List<AdminServer>,
    enabledServerIds: Set<String>,
    status: AlertDeliveryStatus,
    permissionRefused: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onServerChange: (serverId: String, enabled: Boolean) -> Unit,
    onShowDetailsChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(
            stringResource(R.string.alerts_section_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        SwitchRow(
            title = stringResource(R.string.alerts_master_label),
            description = stringResource(R.string.alerts_master_description),
            checked = enabled,
            onChange = onEnabledChange,
            modifier = Modifier.testTag(AlertMasterTag),
        )
        if (permissionRefused || status == AlertDeliveryStatus.BlockedBySystem) {
            Text(stringResource(R.string.alerts_blocked_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Warning)
            Text(stringResource(R.string.alerts_blocked_body), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            TextButton(onClick = onOpenSystemSettings, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(AlertSystemSettingsTag)) {
                Text(stringResource(R.string.alerts_open_system_settings))
            }
        }
        Text(
            stringResource(R.string.alerts_servers_heading), style = MaterialTheme.typography.titleSmall,
            color = if (enabled) VeilColors.Text else VeilColors.Muted,
            modifier = Modifier.padding(top = VeilSpacing.Small).semantics { heading() },
        )
        if (servers.isEmpty()) {
            Text(stringResource(R.string.alerts_no_servers), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        servers.forEach { server ->
            SwitchRow(
                title = server.displayName,
                description = null,
                checked = server.id in enabledServerIds,
                // The list can be prepared before the master switch is turned on; it does nothing until then.
                onChange = { onServerChange(server.id, it) },
                modifier = Modifier.testTag(AlertServerTagPrefix + server.id),
            )
        }
        SwitchRow(
            title = stringResource(R.string.alerts_details_label),
            description = stringResource(R.string.alerts_details_description),
            checked = showDetailsOnLockScreen,
            onChange = onShowDetailsChange,
            modifier = Modifier.padding(top = VeilSpacing.Small).testTag(AlertDetailsTag),
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

/**
 * [AlertSettingsSection] wired to this device: turning alerts on asks Android for the notification permission right
 * then (Android 13 and later), and a refusal leaves them off with a way to the system setting. Turning them off, or a
 * server off, takes its open alerts down at once.
 */
@Composable
fun AlertSettings(alerts: AlertPreferences, servers: List<AdminServer>) {
    val context = LocalContext.current
    val enabled by alerts.enabled.collectAsState()
    val details by alerts.showDetailsOnLockScreen.collectAsState()
    val serverIds by alerts.enabledServerIds.collectAsState()
    var systemAllows by remember { mutableStateOf(AlertNotifier.canPost(context)) }
    var refused by remember { mutableStateOf(false) }
    // Coming back from the system's notification settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { systemAllows = AlertNotifier.canPost(context) }

    fun turnOn() {
        refused = false
        AlertNotifier.ensureChannel(context)
        alerts.setEnabled(true)
        systemAllows = AlertNotifier.canPost(context)
        ServerCheckScheduler.reconcile(context)
        ServerCheckScheduler.checkNow(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (stepAfterPermissionAnswer(granted)) {
            EnableAlertsStep.TurnOn -> turnOn()
            else -> refused = true
        }
    }

    AlertSettingsSection(
        enabled = enabled,
        showDetailsOnLockScreen = details,
        servers = servers,
        enabledServerIds = serverIds,
        status = alertDeliveryStatus(enabled, systemAllows),
        permissionRefused = refused && !enabled,
        onEnabledChange = { wanted ->
            if (!wanted) {
                refused = false
                alerts.setEnabled(false)
                AlertNotifier(context, alerts).clearAll()
                ServerCheckScheduler.reconcile(context)
            } else when (stepToEnableAlerts(Build.VERSION.SDK_INT, AlertNotifier.permissionGranted(context))) {
                EnableAlertsStep.TurnOn -> turnOn()
                EnableAlertsStep.AskPermission -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                EnableAlertsStep.StayOff -> refused = true
            }
        },
        onServerChange = { id, on ->
            alerts.setServerEnabled(id, on)
            if (!on) AlertNotifier(context, alerts).clear(id)
            ServerCheckScheduler.reconcile(context)
            if (on) ServerCheckScheduler.checkNow(context)
        },
        onShowDetailsChange = alerts::setShowDetailsOnLockScreen,
        onOpenSystemSettings = { openNotificationSettings(context) },
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
