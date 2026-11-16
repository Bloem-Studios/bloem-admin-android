package com.bloemstudios.admin.push

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.bloemstudios.admin.alerts.AlertNotifier
import com.bloemstudios.admin.alerts.AlertPreferences
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PushSectionTag = "push-section"
const val PushServerTagPrefix = "push-server:"
const val PushKindTagPrefix = "push-kind:"
const val PushAllowTag = "push-allow-notifications"
const val PushSystemSettingsTag = "push-open-system-settings"

/** The words that explain a server's push status. [PushStatus.Registered] and [PushStatus.Failed] have their own rows. */
@StringRes
internal fun pushStatusText(status: PushStatus): Int = when (status) {
    PushStatus.Unknown -> R.string.push_status_unknown
    PushStatus.NotAvailable -> R.string.push_status_not_available
    PushStatus.NotConfigured -> R.string.push_status_not_configured
    PushStatus.NeedsPermission -> R.string.push_status_needs_permission
    PushStatus.Registered -> R.string.push_status_registered
    PushStatus.Failed -> R.string.push_status_failed
}

/** Which kinds a server's section offers: the two local ones always, the rest only where the server can push. */
internal fun kindsToShow(status: PushStatus): List<PushKindInfo> =
    if (status == PushStatus.NotAvailable || status == PushStatus.NotConfigured) PushKinds.all.filter { it.local } else PushKinds.all

/**
 * The "Notifications" part of App settings, state held by the caller. A plain Silo server says plainly that push is not
 * available there and that only the server-down checks apply; it gets no error and no switches that would do nothing.
 */
@Composable
fun PushSettingsSection(
    servers: List<AdminServer>,
    statuses: Map<String, PushStatus>,
    disabledKinds: Map<String, Set<String>>,
    /** Servers the admin switched on in "Servers to watch": that switch is the `admin.server_down` kind. */
    watchedServerIds: Set<String>,
    notificationsAllowed: Boolean,
    onKindChange: (server: AdminServer, kind: String, enabled: Boolean) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(Modifier.testTag(PushSectionTag), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(
            stringResource(R.string.push_section_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = VeilSpacing.Medium).semantics { heading() },
        )
        Text(stringResource(R.string.push_section_description), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        val anyNeedsPermission = servers.any { (statuses[it.id] ?: PushStatus.Unknown) == PushStatus.NeedsPermission }
        if (anyNeedsPermission || !notificationsAllowed && servers.any { statuses[it.id] == PushStatus.Registered }) {
            Text(stringResource(R.string.alerts_blocked_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Warning)
            Text(stringResource(R.string.push_permission_body), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            TextButton(onClick = onAllowNotifications, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PushAllowTag)) {
                Text(stringResource(R.string.push_allow))
            }
            TextButton(onClick = onOpenSystemSettings, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PushSystemSettingsTag)) {
                Text(stringResource(R.string.alerts_open_system_settings))
            }
        }
        if (servers.isEmpty()) {
            Text(stringResource(R.string.alerts_no_servers), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        servers.forEach { server ->
            val status = statuses[server.id] ?: PushStatus.Unknown
            val off = disabledKinds[server.id].orEmpty()
            Column(Modifier.testTag(PushServerTagPrefix + server.id), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(
                    server.displayName, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = VeilSpacing.Small).semantics { heading() },
                )
                Text(stringResource(pushStatusText(status)), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                kindsToShow(status).forEach { kind ->
                    KindRow(
                        title = stringResource(kind.label),
                        checked = if (kind.id == PushKind.SERVER_DOWN) server.id in watchedServerIds else kind.id !in off,
                        onChange = { onKindChange(server, kind.id, it) },
                        modifier = Modifier.testTag(PushKindTagPrefix + server.id + ":" + kind.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun KindRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

/**
 * [PushSettingsSection] wired to this device. Opening it reads every server again (a sync can take a moment), so the
 * status shown is what the servers say now; a kind switch is kept here first and then sent to the server.
 */
@Composable
fun PushSettings(alerts: AlertPreferences, servers: List<AdminServer>) {
    val context = LocalContext.current
    val preferences = remember { PushRuntime.preferences(context) }
    val statuses by preferences.statuses.collectAsState()
    val disabled by preferences.disabledKinds.collectAsState()
    val watched by alerts.enabledServerIds.collectAsState()
    var allowed by remember { mutableStateOf(AlertNotifier.canPost(context)) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = AlertNotifier.canPost(context) }
    LaunchedEffect(servers.map { it.id }, allowed) {
        withContext(Dispatchers.IO) { runCatching { PushRuntime.sync(context) } }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted && AlertNotifier.canPost(context)
        if (granted) PushScheduler.syncSoon(context)
    }
    PushSettingsSection(
        servers = servers,
        statuses = statuses,
        disabledKinds = disabled,
        watchedServerIds = watched,
        notificationsAllowed = allowed,
        onKindChange = { server, kind, enabled ->
            if (kind == PushKind.SERVER_DOWN) {
                // The same switch as "Servers to watch": it starts or stops this server's local check.
                alerts.setServerEnabled(server.id, enabled)
                if (!enabled) AlertNotifier(context, alerts).clear(server.id)
                ServerCheckScheduler.reconcile(context)
                if (enabled) ServerCheckScheduler.checkNow(context)
            } else {
                // Kept on this device at once; the server is told in the background. A local kind never leaves the device.
                preferences.setKindEnabled(server.id, kind, enabled)
                scope.launch(Dispatchers.IO) { runCatching { PushRuntime.setKind(context, server, kind, enabled) } }
            }
        },
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= PERMISSION_API) permission.launch(POST_NOTIFICATIONS) else openNotificationSettings(context)
        },
        onOpenSystemSettings = { openNotificationSettings(context) },
    )
}

/**
 * Asks for the notification permission once, at the first moment push is useful: a signed-in Bloem server that can push to
 * this app, and the first page has shown data ([FirstDataLoad]). Never at launch, never straight after sign-in and never for a
 * server that cannot push. Put once near the root, after sign-in.
 */
@Composable
fun PushPermissionPrompt(servers: List<AdminServer>) {
    val context = LocalContext.current
    val preferences = remember { PushRuntime.preferences(context) }
    val statuses by preferences.statuses.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PushScheduler.syncSoon(context)
    }
    LaunchedEffect(servers.map { it.id }) {
        if (servers.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { PushRuntime.sync(context) } }
    }
    val firstPageLoaded by FirstDataLoad.loaded.collectAsState()
    LaunchedEffect(statuses, firstPageLoaded) {
        val wanted = shouldAskForPermission(
            sdk = Build.VERSION.SDK_INT, alreadyAsked = preferences.permissionAsked, granted = AlertNotifier.permissionGranted(context),
            anyServerCanPush = servers.any { (statuses[it.id] ?: PushStatus.Unknown) in PUSHABLE },
            firstPageLoaded = firstPageLoaded,
        )
        if (wanted) {
            preferences.markPermissionAsked()
            permission.launch(POST_NOTIFICATIONS)
        }
    }
}

private val PUSHABLE = setOf(PushStatus.NeedsPermission, PushStatus.Registered)
private const val PERMISSION_API = 33

/** The permission's own name, so lint does not read a reference to it as use on a device older than Android 13. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/** The one rule for asking: first useful moment, once, only where Android asks, and only for a server that can push. */
internal fun shouldAskForPermission(
    sdk: Int, alreadyAsked: Boolean, granted: Boolean, anyServerCanPush: Boolean, firstPageLoaded: Boolean,
): Boolean = sdk >= PERMISSION_API && !alreadyAsked && !granted && anyServerCanPush && firstPageLoaded

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
