package com.bloemstudios.admin.serverconfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

const val SettingsSaveBarTag = "settings-save-bar"
const val SettingsSaveButtonTag = "settings-save"
const val SettingsDiscardButtonTag = "settings-discard"

/** The bar under a page with unsaved edits: how many, Discard and Save. */
@Composable
fun SettingsSaveBar(changeCount: Int, saving: Boolean, canSave: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    if (changeCount == 0) return
    Row(
        modifier
            .fillMaxWidth()
            .background(VeilColors.Card)
            .border(BorderStroke(1.dp, VeilColors.Hairline))
            .padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small)
            .testTag(SettingsSaveBarTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        Text(
            pluralStringResource(R.plurals.settings_page_changes, changeCount, changeCount),
            style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.testTag(SettingsDiscardButtonTag)) { Text(stringResource(R.string.settings_page_discard)) }
        Button(onClick = onSave, enabled = canSave && !saving, modifier = Modifier.testTag(SettingsSaveButtonTag)) { Text(stringResource(R.string.action_save)) }
    }
}

/** What a catalogue page needs from its surroundings. */
class SettingsPageActions(
    val onEdit: (key: String, value: String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onDismissError: () -> Unit,
)

/**
 * One named page: its groups of labelled controls, the extras it carries (test email, provider test, server channels,
 * Redis check), and the save bar. The page's controls show only for keys the server reports.
 */
@Composable
fun SettingsPageContent(
    page: SettingsPage,
    state: SettingsPagesState,
    client: ApiClient,
    tests: SettingsTestsViewModel,
    testResults: Map<String, TestState>,
    actions: SettingsPageActions,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    savedNotice: Int? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val snapshot = state.snapshot ?: return
    val reported = snapshot.values.keys
    val groups = page.shownGroups(reported)
    val changeCount = state.draft.changes(page.keys).size
    val invalid = page.settings.any { def ->
        val kind = def.kind
        kind is SettingKind.Number && state.draft.isDirty(def.key) && kind.problem(state.draft.value(def.key)) != null
    }
    LaunchedEffect(focusKey, groups) {
        if (focusKey == null) return@LaunchedEffect
        var index = 1 // the banners come first
        for (group in groups) { index += 1; for (def in group.settings) { if (def.key == focusKey) { listState.scrollToItem(index); return@LaunchedEffect }; index += 1 } }
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("settings-page:${page.id}"),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            item(key = "banners") {
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    state.saveError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = actions.onDismissError, tag = "settings-save-error") }
                    if (state.conflict) {
                        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                            Text(stringResource(R.string.settings_changed_on_server), style = MaterialTheme.typography.titleSmall, color = VeilColors.Warning, modifier = Modifier.testTag("settings-conflict"))
                            Text(stringResource(R.string.settings_page_conflict_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                        }
                    }
                    savedNotice?.let { MessageBanner(stringResource(it), isError = false, onDismiss = {}, tag = "settings-saved") }
                }
            }
            groups.forEach { group ->
                item(key = "group:${group.titleRes}") {
                    Column(Modifier.padding(top = VeilSpacing.Medium), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                        Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, modifier = Modifier.semantics { heading() })
                        if (group.noteRes != 0) Text(stringResource(group.noteRes), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                }
                group.settings.forEach { def ->
                    item(key = "setting:${def.key}") {
                        SettingControl(
                            def,
                            SettingControlState(
                                value = state.draft.value(def.key),
                                restartOnly = snapshot.restart.requiresRestart(def.key),
                                managedByEnv = def.key in snapshot.sensitive.managed_by_env,
                                secretConfigured = def.key in snapshot.sensitive.configured,
                                enabled = !state.saving && snapshot.validator.isNotBlank(),
                            ),
                            onChange = { actions.onEdit(def.key, it) },
                        )
                    }
                }
                if (group.settings.any { it.key == "email.smtp_password" } && PageExtra.EmailTest in page.extras) {
                    item(key = "extra:email-test") { EmailTestRow(client, tests, testResults, hasUnsavedEdits = changeCount > 0) }
                }
            }
            if (PageExtra.SignInProvider in page.extras) item(key = "extra:signin") { SignInProviderSection(client, tests, testResults) }
            if (PageExtra.ServerChannels in page.extras) item(key = "extra:channels") { ServerChannelsSection(client, tests, testResults) }
            if (PageExtra.RedisCheck in page.extras) item(key = "extra:redis") {
                ConnectionCheckRow("redis", R.string.settings_check_redis, client, tests, testResults)
            }
        }
        SettingsSaveBar(changeCount, state.saving, canSave = !invalid, onSave = actions.onSave, onDiscard = actions.onDiscard)
    }
}
