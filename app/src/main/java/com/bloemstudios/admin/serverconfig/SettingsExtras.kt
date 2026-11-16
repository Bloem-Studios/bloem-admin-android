package com.bloemstudios.admin.serverconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.plugins.PluginInstallation
import com.bloemstudios.admin.network.api.ServerChannel
import com.bloemstudios.admin.network.api.getExternalSignInCapabilities
import com.bloemstudios.admin.network.api.listPluginInstallations
import com.bloemstudios.admin.network.api.listServerChannels
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch

/** The answer to one test: Passed or Failed in plain words, then the server's own reason and any steps. */
@Composable
fun TestResultLine(state: TestState?, modifier: Modifier = Modifier) {
    if (state == null) return
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        when (state) {
            TestState.Running -> StatusLabel(stringResource(R.string.settings_test_running))
            is TestState.Finished -> {
                val head = when {
                    state.passed && state.durationMs != null -> stringResource(R.string.settings_test_passed_in, state.durationMs)
                    state.passed -> stringResource(R.string.settings_test_passed)
                    state.couldNotRun -> stringResource(R.string.settings_test_could_not_run)
                    else -> stringResource(R.string.settings_test_failed)
                }
                Text(head, style = MaterialTheme.typography.labelLarge, color = if (state.passed) VeilColors.Ok else VeilColors.Danger)
                state.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
                if (!state.passed && state.reason == null) {
                    Text(stringResource(R.string.settings_test_no_reason), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                state.httpStatus?.takeIf { !state.passed || !state.couldNotRun }?.let {
                    Text(stringResource(R.string.settings_test_http_status, it), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                state.steps.forEach { step ->
                    StatusLabel(
                        if (step.message.isBlank()) step.label else "${step.label}: ${step.message}",
                        tone = if (step.passed) StatusTone.Quiet else StatusTone.Danger, dot = !step.passed,
                    )
                }
            }
        }
    }
}

/** Sends one real message through the saved mail settings. Held back while there are unsaved edits, because the test reads the saved ones. */
@Composable
fun EmailTestRow(client: ApiClient, tests: SettingsTestsViewModel, results: Map<String, TestState>, hasUnsavedEdits: Boolean) {
    var recipient by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = results[TestKeys.EMAIL]
    RowCard(Modifier.testTag("settings-email-test")) {
        OutlinedTextField(
            value = recipient, onValueChange = { recipient = it; invalid = false },
            label = { Text(stringResource(R.string.settings_email_recipient)) },
            placeholder = { Text(stringResource(R.string.settings_email_recipient_hint), color = VeilColors.Muted) },
            singleLine = true, isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.settings_email_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasUnsavedEdits) Text(stringResource(R.string.settings_email_save_first), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        OutlinedButton(
            enabled = state != TestState.Running && !hasUnsavedEdits,
            onClick = {
                if (!isPlausibleEmail(recipient)) invalid = true
                else scope.launch { tests.sendTestEmail(client, recipient) }
            },
        ) { Text(stringResource(R.string.settings_email_send_test)) }
        TestResultLine(state)
    }
}

/** The connection checks the server offers, with the settings name each one tests. */
internal val CONNECTION_CHECKS: List<Pair<String, Int>> = listOf(
    "redis" to R.string.settings_check_redis,
    "s3_public" to R.string.settings_check_s3_public,
    "s3_private" to R.string.settings_check_s3_private,
    "meilisearch" to R.string.settings_check_meilisearch,
    "mdblist" to R.string.settings_check_mdblist,
    "ai_chat" to R.string.settings_check_ai_chat,
    "ai_transcription" to R.string.settings_check_ai_transcription,
)

/** One connection check: its name, a Check button and the pass or fail with the server's reason. */
@Composable
fun ConnectionCheckRow(kind: String, nameRes: Int, client: ApiClient, tests: SettingsTestsViewModel, results: Map<String, TestState>) {
    val scope = rememberCoroutineScope()
    val state = results[TestKeys.check(kind)]
    RowCard(Modifier.testTag("settings-check:$kind")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(stringResource(nameRes), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = state != TestState.Running, onClick = { scope.launch { tests.checkConnection(client, kind) } }) {
                Text(stringResource(R.string.settings_check_run))
            }
        }
        TestResultLine(state)
    }
}

/** The server channels with their last failure and a Send test button each. Hidden on a server that does not serve them. */
@Composable
fun ServerChannelsSection(client: ApiClient, tests: SettingsTestsViewModel, results: Map<String, TestState>, preloaded: List<ServerChannel>? = null) {
    var channels by remember(client) { mutableStateOf(preloaded) }
    var failed by remember(client) { mutableStateOf(false) }
    var hidden by remember(client) { mutableStateOf(false) }
    var attempt by remember(client) { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client, attempt) {
        if (preloaded != null) return@LaunchedEffect
        failed = false
        try { channels = client.listServerChannels() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: ApiError) { if (e.statusCode == 404 || e.statusCode == 503) hidden = true else failed = true }
        catch (_: Exception) { failed = true }
    }
    if (hidden) return
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(
            stringResource(R.string.settings_channels_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            modifier = Modifier.padding(top = VeilSpacing.Medium),
        )
        if (failed) {
            RowCard(onClick = { attempt++ }) { StatusLabel(stringResource(R.string.settings_channels_load_failed), tone = StatusTone.Warning, dot = true) }
        }
        val list = channels
        if (list != null && list.isEmpty()) Text(stringResource(R.string.settings_channels_none), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        list?.forEach { channel ->
            val state = results[TestKeys.channel(channel.id)]
            RowCard(Modifier.testTag("settings-channel:${channel.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                        StatusLabel(
                            stringResource(if (channel.enabled) R.string.settings_channel_enabled else R.string.settings_channel_disabled),
                            tone = if (channel.enabled && channel.consecutive_failures == 0) StatusTone.Quiet else StatusTone.Warning,
                        )
                    }
                    OutlinedButton(enabled = state != TestState.Running, onClick = { scope.launch { tests.testChannel(client, channel.id) } }) {
                        Text(stringResource(R.string.settings_channel_test))
                    }
                }
                val failure = channel.last_failure_message?.takeIf { it.isNotBlank() }
                    ?: channel.disabled_reason?.takeIf { it.isNotBlank() }
                if (failure != null) {
                    val whenText = channel.last_failure_at?.let { formatDateTime(it) }
                    Text(
                        stringResource(R.string.settings_channel_last_failure, listOfNotNull(whenText, failure).joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                    )
                }
                TestResultLine(state)
            }
        }
    }
}

/** What the sign-in provider section shows: each installed sign-in provider, whether it is on, and whether it can be tested. */
data class SignInProviderRow(val installation: PluginInstallation, val name: String, val on: Boolean, val capabilityId: String, val testable: Boolean)

/**
 * The server's single sign-on providers (not network sign-in): from the plugin installations and the external sign-in
 * capabilities. Empty on a server without external sign-in, so the section is not drawn.
 */
suspend fun loadSignInProviders(client: ApiClient): List<SignInProviderRow> {
    val caps = client.getExternalSignInCapabilities()
    if (!caps.available) return emptyList()
    return client.listPluginInstallations().mapNotNull { installation ->
        val capability = installation.capabilities.firstOrNull { it.type == "auth_provider.v1" } ?: return@mapNotNull null
        if (capability.sign_in_mode == "network") return@mapNotNull null
        val on = installation.auth_bindings.firstOrNull { it.capability_id == capability.id }?.enabled == true
        val name = installation.presentation?.display_name?.trim()?.takeIf { it.isNotEmpty() }
            ?: capability.display_name.trim().takeIf { it.isNotEmpty() } ?: installation.plugin_id
        SignInProviderRow(installation, name, on, capability.id, testable = caps.connection_test && capability.declaresConnectionTest && installation.enabled)
    }
}

@Composable
fun SignInProviderSection(client: ApiClient, tests: SettingsTestsViewModel, results: Map<String, TestState>, preloaded: List<SignInProviderRow>? = null) {
    var providers by remember(client) { mutableStateOf(preloaded) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client) {
        if (preloaded != null) return@LaunchedEffect
        // A server without external sign-in, or one that refuses the read, simply has no provider section.
        providers = try { loadSignInProviders(client) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
    }
    val list = providers.orEmpty()
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(
            stringResource(R.string.settings_signin_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            modifier = Modifier.padding(top = VeilSpacing.Medium),
        )
        list.forEach { row ->
            val state = results[TestKeys.signIn(row.installation.id)]
            RowCard(Modifier.testTag("settings-signin:${row.installation.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                        StatusLabel(stringResource(if (row.on) R.string.settings_signin_on else R.string.settings_signin_off))
                    }
                    if (row.testable) {
                        OutlinedButton(
                            enabled = state != TestState.Running,
                            onClick = { scope.launch { tests.testSignIn(client, row.installation.id, row.capabilityId) } },
                        ) { Text(stringResource(R.string.settings_signin_test)) }
                    }
                }
                TestResultLine(state)
            }
        }
    }
}
