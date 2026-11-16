package com.bloemstudios.admin.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.activity.stackFields
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.logs.plainAddress
import com.bloemstudios.admin.ui.components.DetailField
import com.bloemstudios.admin.ui.components.DetailPair
import com.bloemstudios.admin.ui.components.DetailSheet
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.rememberTextSharer
import com.bloemstudios.admin.ui.shareText
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.formatDateTime

/** The words a change is written with, in the user's language. */
@Composable
internal fun rememberAuditWords(): AuditWords = AuditWords(
    absent = stringResource(R.string.audit_value_absent),
    allAssignable = stringResource(R.string.audit_value_all_assignable),
    allLibraries = stringResource(R.string.audit_value_all_libraries),
    changed = stringResource(R.string.audit_value_changed),
    fullAccess = stringResource(R.string.audit_value_full_access),
    inherit = stringResource(R.string.audit_value_inherit),
    none = stringResource(R.string.audit_value_none),
    pinCleared = stringResource(R.string.audit_value_pin_cleared),
    pinNotSet = stringResource(R.string.audit_value_pin_not_set),
    pinSet = stringResource(R.string.audit_value_pin_set),
    unset = stringResource(R.string.audit_value_unset),
)

/** "field: before → after" for each change the request made. */
@Composable
internal fun auditChangeLines(entry: AuditLogEntry, words: AuditWords): List<String> =
    entry.changes.orEmpty().map { stringResource(R.string.audit_change_line, auditFieldLabel(it.field), auditChangeLine(it, entry.target_type, words)) }

@Composable
internal fun auditTarget(entry: AuditLogEntry): String? =
    auditTargetText(entry.target_type, entry.target_id, stringResource(R.string.audit_target_api_key))

/**
 * Who did it: the account's name when it is known, otherwise "User 7"; "maria as jonas" when an administrator acted as
 * someone else. Null for a request nobody was signed in for.
 */
@Composable
internal fun auditActor(entry: AuditLogEntry, names: Map<String, String>): String? {
    @Composable fun name(id: String) = names[id] ?: stringResource(R.string.audit_user_line, id)
    val user = entry.user_id
    val impersonator = entry.impersonator_user_id
    return when {
        impersonator != null && user != null -> stringResource(R.string.audit_actor_impersonated, name(impersonator), name(user))
        impersonator != null -> name(impersonator)
        user != null -> name(user)
        else -> null
    }
}

/**
 * One audited request in full: what it did and changed, the request, the address and client behind it, how long it took,
 * and the request, node and session IDs that tie it to Logs. [onOpenLogs] is offered where the page can open Logs.
 */
@Composable
internal fun AuditDetailSheet(
    entry: AuditLogEntry,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onOpenLogs: ((LogsFocus) -> Unit)? = null,
) {
    val words = rememberAuditWords()
    val sharer = rememberTextSharer()
    val stacked = stackFields()
    val headline = entry.action?.takeIf(String::isNotBlank) ?: stringResource(R.string.audit_http_request)
    val changes = auditChangeLines(entry, words)
    val target = auditTarget(entry)
    val time = formatDateTime(entry.timestamp) ?: entry.timestamp
    val request = stringResource(R.string.audit_method_path, entry.method, entry.path)
    val actor = auditActor(entry, names)
    val impersonator = entry.impersonator_user_id?.let { names[it] ?: stringResource(R.string.audit_user_line, it) }
    val user = entry.user_id?.let { names[it] ?: stringResource(R.string.audit_user_line, it) }
    val duration = stringResource(R.string.logs_duration_ms, entry.duration_ms.toString())
    val ip = plainAddress(entry.client_ip)
    val labels = AuditLabels(
        time = stringResource(R.string.audit_detail_time), action = stringResource(R.string.audit_detail_action),
        target = stringResource(R.string.audit_detail_target), changes = stringResource(R.string.audit_detail_changes),
        request = stringResource(R.string.audit_detail_request), status = stringResource(R.string.audit_detail_status),
        duration = stringResource(R.string.audit_detail_duration), ip = stringResource(R.string.audit_detail_client_ip),
        userAgent = stringResource(R.string.audit_detail_user_agent), user = stringResource(R.string.audit_detail_user),
        impersonator = stringResource(R.string.audit_detail_impersonator), requestId = stringResource(R.string.audit_detail_request_id),
        node = stringResource(R.string.audit_detail_node), session = stringResource(R.string.audit_detail_session),
        playback = stringResource(R.string.audit_detail_playback_session),
    )
    val body = shareText(
        headline,
        listOf(
            labels.time to time, labels.target to target, labels.changes to changes.joinToString("\n").ifEmpty { null },
            labels.request to request, labels.status to entry.status_code.toString(), labels.duration to duration,
            labels.ip to ip, labels.userAgent to entry.user_agent, labels.user to user, labels.impersonator to impersonator,
            labels.requestId to entry.request_id, labels.node to entry.node_id, labels.session to entry.session_id,
            labels.playback to entry.playback_session_id,
        ),
    )
    val playback = entry.playback_session_id?.takeIf(String::isNotBlank)
    val requestId = entry.request_id?.takeIf(String::isNotBlank)
    var copied by remember(entry.id) { mutableStateOf(false) }
    DetailSheet(
        title = headline,
        subtitle = listOfNotNull(time, actor).joinToString(" · "),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("audit-detail"),
        actions = {
            OutlinedButton({ sharer.copy(body); copied = true }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("audit-detail-copy")) {
                Text(stringResource(if (copied) R.string.detail_copied else R.string.detail_copy))
            }
            OutlinedButton({ sharer.share(body) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("audit-detail-share")) {
                Text(stringResource(R.string.detail_share))
            }
            if (onOpenLogs != null && playback != null) TextButton({ onOpenLogs(LogsFocus(playbackSessionId = playback)) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("audit-detail-session-logs")) {
                Text(stringResource(R.string.audit_detail_view_session))
            }
            if (onOpenLogs != null && requestId != null) TextButton({ onOpenLogs(LogsFocus(requestId = requestId)) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("audit-detail-request-logs")) {
                Text(stringResource(R.string.audit_detail_view_request))
            }
        },
    ) {
        if (changes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
            Text(labels.changes, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
            changes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text) }
        }
        DetailField(labels.target, target)
        DetailField(labels.request, request)
        DetailPair({ DetailField(labels.status, entry.status_code.toString(), it) }, { DetailField(labels.duration, duration, it) }, stacked)
        DetailField(labels.ip, ip)
        DetailField(labels.userAgent, entry.user_agent)
        DetailPair({ DetailField(labels.user, user, it) }, { DetailField(labels.impersonator, impersonator, it) }, stacked)
        DetailField(labels.requestId, requestId)
        DetailField(labels.node, entry.node_id)
        DetailField(labels.session, entry.session_id)
        DetailField(labels.playback, playback)
    }
}

private class AuditLabels(
    val time: String, val action: String, val target: String, val changes: String, val request: String, val status: String,
    val duration: String, val ip: String, val userAgent: String, val user: String, val impersonator: String,
    val requestId: String, val node: String, val session: String, val playback: String,
)
