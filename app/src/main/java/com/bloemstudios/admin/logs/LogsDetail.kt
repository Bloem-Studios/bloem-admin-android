package com.bloemstudios.admin.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.activity.stackFields
import com.bloemstudios.admin.ui.components.DetailField
import com.bloemstudios.admin.ui.components.DetailPair
import com.bloemstudios.admin.ui.components.DetailSheet
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.Stat
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.rememberTextSharer
import com.bloemstudios.admin.ui.shareText
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.detail.formatDateTime

/** The level as the sentence-case word the list uses. */
@Composable
internal fun levelLabel(level: String): String = when (level.lowercase()) {
    "error" -> stringResource(R.string.logs_level_error)
    "warn", "warning" -> stringResource(R.string.logs_level_warn)
    "info" -> stringResource(R.string.logs_level_info)
    "debug" -> stringResource(R.string.logs_level_debug)
    else -> level.replaceFirstChar { it.uppercase() }
}

/**
 * Everything one log line carries: when, where it came from, the request's method, status and duration (read from its
 * attributes), who, which sessions, and the raw attributes. Copy and Share send the whole record; the two links narrow
 * the list to the line's playback session or request.
 */
@Composable
internal fun LogDetailSheet(
    entry: OperationalLogEntry,
    onDismiss: () -> Unit,
    onFilterPlaybackSession: (String) -> Unit,
    onFilterRequest: (String) -> Unit,
) {
    val sharer = rememberTextSharer()
    val stacked = stackFields()
    val time = formatDateTime(entry.timestamp) ?: entry.timestamp
    val level = levelLabel(entry.level)
    val method = entry.attrText("method")
    val status = entry.attrText("status")
    val duration = entry.durationMs()?.let { stringResource(R.string.logs_duration_ms, it) }
    val ip = (entry.clientIpOrAttr())?.let(::plainAddress)
    val session = entry.sessionOrAttr()
    val playback = entry.playbackSessionOrAttr()
    val request = entry.request_id?.takeIf(String::isNotBlank)
    val path = entry.attrText("path")
    val attributes = entry.attrs?.takeIf { it.isNotEmpty() }.prettyAttributes()
    val labels = LogLabels(
        time = stringResource(R.string.logs_detail_time), level = stringResource(R.string.logs_detail_level),
        component = stringResource(R.string.logs_detail_component), request = stringResource(R.string.logs_detail_request_id),
        node = stringResource(R.string.logs_detail_node), method = stringResource(R.string.logs_detail_method),
        status = stringResource(R.string.logs_detail_status), duration = stringResource(R.string.logs_detail_duration),
        ip = stringResource(R.string.logs_detail_client_ip), user = stringResource(R.string.logs_detail_user),
        session = stringResource(R.string.logs_detail_session), playback = stringResource(R.string.logs_detail_playback_session),
        path = stringResource(R.string.logs_detail_path), attributes = stringResource(R.string.logs_detail_attributes),
    )
    val body = shareText(
        entry.message,
        listOf(
            labels.time to time, labels.level to level, labels.component to entry.component, labels.request to request,
            labels.node to entry.node_id, labels.method to method, labels.status to status, labels.duration to duration,
            labels.ip to ip, labels.user to entry.user_id, labels.session to session, labels.playback to playback,
            labels.path to path, labels.attributes to attributes.takeIf { entry.attrs?.isNotEmpty() == true },
        ),
    )
    var copied by remember(entry.id) { mutableStateOf(false) }
    DetailSheet(
        title = entry.message,
        subtitle = listOf(entry.component, level, time).joinToString(" · "),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("log-detail"),
        actions = {
            OutlinedButton({ sharer.copy(body); copied = true }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("log-detail-copy")) {
                Text(stringResource(if (copied) R.string.detail_copied else R.string.detail_copy))
            }
            OutlinedButton({ sharer.share(body) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("log-detail-share")) {
                Text(stringResource(R.string.detail_share))
            }
            if (playback != null) TextButton({ onFilterPlaybackSession(playback) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("log-detail-session-logs")) {
                Text(stringResource(R.string.logs_detail_view_session))
            }
            if (request != null) TextButton({ onFilterRequest(request) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("log-detail-request-logs")) {
                Text(stringResource(R.string.logs_detail_view_request))
            }
        },
    ) {
        DetailPair({ DetailField(labels.method, method, it) }, { DetailField(labels.status, status, it) }, stacked)
        DetailPair({ DetailField(labels.duration, duration, it) }, { DetailField(labels.node, entry.node_id, it) }, stacked)
        DetailField(labels.ip, ip)
        DetailField(labels.user, entry.user_id)
        DetailField(labels.request, request)
        DetailField(labels.session, session)
        DetailField(labels.playback, playback)
        DetailField(labels.path, path)
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
            Text(labels.attributes, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
            SelectionContainer {
                Text(
                    attributes,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = VeilColors.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VeilColors.Card, RoundedCornerShape(VeilShapes.InnerCorner))
                        .padding(VeilSpacing.Medium)
                        .testTag("log-detail-attributes"),
                )
            }
        }
    }
}

private class LogLabels(
    val time: String, val level: String, val component: String, val request: String, val node: String, val method: String,
    val status: String, val duration: String, val ip: String, val user: String, val session: String, val playback: String,
    val path: String, val attributes: String,
)

/** The web's short form of a long identifier: "3f2a9c1e…a0b4". */
fun shortId(value: String): String = if (value.length <= 12) value else "${value.take(8)}…${value.takeLast(4)}"

/**
 * What a playback session left in the logs, over the rows loaded for it: how many application, FFmpeg and audit lines,
 * when it was first and last seen, and on which nodes. A count with older rows still to load reads "N or more". The two
 * buttons narrow the list to FFmpeg lines or leave the session.
 */
@Composable
internal fun PlaybackSessionSummaryCard(
    playbackSessionId: String,
    summary: PlaybackSessionSummary,
    appHasMore: Boolean,
    auditHasMore: Boolean,
    auditAvailable: Boolean,
    ffmpegOnly: Boolean,
    onToggleFfmpeg: () -> Unit,
    onClear: () -> Unit,
) {
    @Composable fun count(n: Int, more: Boolean) = if (more) stringResource(R.string.logs_summary_count_more, n) else n.toString()
    val app = count(summary.appCount, appHasMore)
    val ffmpeg = count(summary.ffmpegCount, appHasMore)
    val audit = if (auditAvailable) count(summary.auditCount, auditHasMore) else stringResource(R.string.detail_no_value)
    val first = summary.firstSeen?.let { formatDateTime(it) ?: it }
    val last = summary.lastSeen?.let { formatDateTime(it) ?: it }
    val description = stringResource(R.string.logs_summary_description, shortId(playbackSessionId), app, ffmpeg, audit)
    RowCard(Modifier.testTag("logs-session-summary")) {
        Text(
            stringResource(R.string.logs_summary_title), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
        )
        Text(
            shortId(playbackSessionId),
            style = MaterialTheme.typography.titleSmall.tabular(), color = VeilColors.Text,
            modifier = Modifier.semantics { contentDescription = description },
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            Modifier.padding(top = VeilSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
        ) {
            Stat(stringResource(R.string.logs_summary_app), app)
            Stat(stringResource(R.string.logs_summary_ffmpeg), ffmpeg)
            Stat(stringResource(R.string.logs_summary_audit), audit)
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            Modifier.padding(top = VeilSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
        ) {
            Stat(stringResource(R.string.logs_summary_first), first ?: stringResource(R.string.detail_no_value))
            Stat(stringResource(R.string.logs_summary_last), last ?: stringResource(R.string.detail_no_value))
            Stat(stringResource(R.string.logs_summary_nodes), summary.nodes.joinToString(", ").ifEmpty { stringResource(R.string.detail_no_value) })
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            TextButton(onToggleFfmpeg, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("logs-summary-ffmpeg")) {
                Text(stringResource(if (ffmpegOnly) R.string.logs_summary_all_components else R.string.logs_summary_ffmpeg_only))
            }
            TextButton(onClear, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("logs-summary-clear")) {
                Text(stringResource(R.string.logs_summary_clear))
            }
        }
    }
}
