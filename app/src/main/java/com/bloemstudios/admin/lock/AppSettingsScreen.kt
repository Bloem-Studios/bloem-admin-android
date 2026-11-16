package com.bloemstudios.admin.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ui.shell.ShellTopBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

const val ThemeOptionTagPrefix = "app-settings-theme:"

/**
 * Settings, as a page of its own (a back arrow and the title, like Manage servers), in the shared order: Appearance, App lock,
 * then Notifications (the server alerts and push). Sign out is not repeated here: it is in the drawer's foot.
 * On a tablet the content keeps a readable width, centred.
 */
@Composable
fun AppSettingsScreen(
    requireDeviceUnlock: Boolean,
    onRequireDeviceUnlockChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    /** The "Server alerts" section; null where the host has none (previews and single-screen tests). */
    alertsContent: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = VeilColors.Canvas,
        topBar = {
            ShellTopBar(
                title = stringResource(R.string.app_settings_title),
                context = null,
                enabled = true,
                activityScope = null,
                onContextClick = {},
                onMenuClick = null,
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = AppSettingsMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Large),
            ) {
                AppSettingsBody(requireDeviceUnlock, onRequireDeviceUnlockChanged, themeMode, onThemeModeChanged, alertsContent)
            }
        }
    }
}

private val AppSettingsMaxWidth = 640.dp

/** What the page holds, apart from its bar and scrolling, so the debug fixture screen can draw it in a frame. */
@Composable
internal fun AppSettingsBody(
    requireDeviceUnlock: Boolean,
    onRequireDeviceUnlockChanged: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    alertsContent: (@Composable () -> Unit)?,
) {
    val unlockLabel = stringResource(R.string.app_settings_device_unlock)
    val modes = ThemeMode.entries
    val modeLabels = modes.map {
        stringResource(when (it) {
            ThemeMode.System -> R.string.app_settings_theme_system
            ThemeMode.Light -> R.string.app_settings_theme_light
            ThemeMode.Dark -> R.string.app_settings_theme_dark
        })
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(
                stringResource(R.string.app_settings_theme),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            SegmentedControl(
                options = modeLabels,
                selectedIndex = modes.indexOf(themeMode),
                onSelect = { onThemeModeChanged(modes[it]) },
                optionModifier = { Modifier.testTag(ThemeOptionTagPrefix + modes[it].storageValue) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
          Text(
              stringResource(R.string.app_settings_lock_section),
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.semantics { heading() },
          )
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            Column(Modifier.weight(1f)) {
                Text(unlockLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.app_settings_device_unlock_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = VeilColors.Muted,
                )
            }
            Switch(
                checked = requireDeviceUnlock,
                onCheckedChange = onRequireDeviceUnlockChanged,
                modifier = Modifier.semantics { contentDescription = unlockLabel },
            )
          }
        }
        alertsContent?.invoke()
    }
}
