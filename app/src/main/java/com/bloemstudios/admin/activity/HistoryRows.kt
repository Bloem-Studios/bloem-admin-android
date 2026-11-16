package com.bloemstudios.admin.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bloemstudios.admin.R
import com.bloemstudios.admin.streams.markColor
import com.bloemstudios.admin.streams.methodLabel
import com.bloemstudios.admin.ui.components.DetailField
import com.bloemstudios.admin.ui.components.DetailPair
import com.bloemstudios.admin.ui.components.DetailSheet
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.rememberTextSharer
import com.bloemstudios.admin.ui.shareText
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.detail.formatDateTime

/** Font scale from which two short fields no longer fit side by side on a phone. */
internal const val STACK_FONT_SCALE = 1.3f

@Composable
internal fun stackFields(): Boolean = LocalDensity.current.fontScale >= STACK_FONT_SCALE

const val HISTORY_ROW_TAG_PREFIX = "history-row-"

/**
 * One finished play: what was watched (an episode leads with its series and SxxExx), by whom, when it ended, how much of
 * it was watched out of its length, and how it was delivered in the play method's chart color. The whole card opens the
 * details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryRow(entry: PlaybackHistoryEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = historyTitle(entry)
    val watch = watchTime(entry)
    val method = historyMethod(entry.play_method)
    val userFallback = stringResource(R.string.history_user_fallback, entry.user_id)
    val watched = stringResource(
        if (watch.estimated) R.string.history_watched_of_estimated else R.string.history_watched_of,
        formatSpan(watch.seconds), formatSpan(entry.duration_seconds),
    )
    RowCard(modifier.testTag(HISTORY_ROW_TAG_PREFIX + entry.session_id), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(
                title.title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            StatusLabel(stringResource(if (entry.completed) R.string.history_status_completed else R.string.history_status_partial))
        }
        title.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(
            historyPeopleLine(entry.username, entry.profile_name, entry.user_id) { userFallback },
            style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
            Text(
                formatDateTime(entry.ended_at ?: entry.started_at) ?: entry.started_at,
                style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
            )
            Text(watched, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Text)
            StatusLabel(methodLabel(method), dot = true, dotColor = method.markColor())
        }
    }
}

@StringRes
internal fun historyDecisionLabelRes(decision: String): Int? = when (decision) {
    "direct" -> R.string.history_decision_direct
    "remux", "copy" -> R.string.history_decision_remux
    "transcode" -> R.string.history_decision_transcode
    else -> null
}

@StringRes
internal fun historyEndReasonLabelRes(reason: String): Int? = when (reason) {
    "stopped" -> R.string.history_end_stopped
    "terminated" -> R.string.history_end_terminated
    "expired" -> R.string.history_end_expired
    "aborted" -> R.string.history_end_aborted
    "replaced" -> R.string.history_end_replaced
    else -> null
}

/**
 * The details of one play: both times, watch time, how it was delivered and ended, who and what client, and the playback
 * session ID that ties it to its logs. [onOpenLogs] is offered where the page can open Logs (one server); it is called with
 * the playback session ID and whether to show only FFmpeg lines.
 */
@Composable
internal fun HistoryDetailSheet(
    entry: PlaybackHistoryEntry,
    onDismiss: () -> Unit,
    onOpenLogs: ((playbackSessionId: String, ffmpegOnly: Boolean) -> Unit)? = null,
) {
    val title = historyTitle(entry)
    val watch = watchTime(entry)
    val method = historyMethod(entry.play_method)
    val sharer = rememberTextSharer()
    val stacked = stackFields()
    val userFallback = stringResource(R.string.history_user_fallback, entry.user_id)
    val started = formatDateTime(entry.started_at) ?: entry.started_at
    val ended = entry.ended_at?.let { formatDateTime(it) ?: it }
    val watched = stringResource(
        if (watch.estimated) R.string.history_watched_of_estimated else R.string.history_watched_of,
        formatSpan(watch.seconds), formatSpan(entry.duration_seconds),
    )
    val methodText = methodLabel(method)
    val video = entry.video_decision?.let { d -> historyDecisionLabelRes(d)?.let { stringResource(it) } ?: d }
    val audio = entry.audio_decision?.let { d -> historyDecisionLabelRes(d)?.let { stringResource(it) } ?: d }
    val endReason = entry.end_reason?.let { r -> historyEndReasonLabelRes(r)?.let { stringResource(it) } ?: r }
    val who = entry.username.ifBlank { userFallback }
    val profile = entry.profile_name.ifBlank { null }
    val client = listOfNotNull(entry.client_name, entry.client_version).joinToString(" ").ifBlank { null }
    val ip = entry.client_ip?.takeIf(String::isNotBlank)?.let { com.bloemstudios.admin.logs.plainAddress(it) }
    val ofLabel = stringResource(R.string.history_detail_end_position)
    val labels = HistoryLabels(
        started = stringResource(R.string.history_detail_started), ended = stringResource(R.string.history_detail_ended),
        watchTime = stringResource(R.string.history_detail_watch_time), endPosition = ofLabel,
        paused = stringResource(R.string.history_detail_paused), method = stringResource(R.string.history_detail_method),
        video = stringResource(R.string.history_detail_video), audio = stringResource(R.string.history_detail_audio),
        endReason = stringResource(R.string.history_detail_end_reason), user = stringResource(R.string.history_detail_user),
        profile = stringResource(R.string.history_detail_profile), client = stringResource(R.string.history_detail_client),
        platform = stringResource(R.string.history_detail_platform), ip = stringResource(R.string.history_detail_ip),
        session = stringResource(R.string.history_detail_session),
    )
    val pausedText = entry.paused_seconds?.takeIf { it > 0.0 }?.let { formatSpan(it) }
    val shareBody = shareText(
        listOfNotNull(title.title, title.detail).joinToString(" · "),
        listOf(
            labels.user to who, labels.profile to profile, labels.started to started, labels.ended to ended,
            labels.watchTime to watched, labels.endPosition to formatSpan(entry.watched_seconds), labels.paused to pausedText,
            labels.method to methodText, labels.video to video, labels.audio to audio, labels.endReason to endReason,
            labels.client to client, labels.platform to entry.device_platform, labels.ip to ip, labels.session to entry.session_id,
        ),
    )
    var copied by remember(entry.session_id) { mutableStateOf(false) }
    DetailSheet(
        title = title.title,
        subtitle = title.detail,
        onDismiss = onDismiss,
        modifier = Modifier.testTag("history-detail"),
        actions = {
            if (onOpenLogs != null) {
                OutlinedButton({ onOpenLogs(entry.session_id, false) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("history-detail-logs")) {
                    Text(stringResource(R.string.history_open_logs))
                }
                OutlinedButton({ onOpenLogs(entry.session_id, true) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("history-detail-ffmpeg")) {
                    Text(stringResource(R.string.history_open_ffmpeg_logs))
                }
            }
            OutlinedButton({ sharer.copy(shareBody); copied = true }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("history-detail-copy")) {
                Text(stringResource(if (copied) R.string.detail_copied else R.string.detail_copy))
            }
            OutlinedButton({ sharer.share(shareBody) }, Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("history-detail-share")) {
                Text(stringResource(R.string.detail_share))
            }
        },
    ) {
        DetailPair({ DetailField(labels.user, who, it) }, { DetailField(labels.profile, profile, it) }, stacked)
        DetailPair({ DetailField(labels.started, started, it) }, { DetailField(labels.ended, ended, it) }, stacked)
        DetailPair({ DetailField(labels.watchTime, watched, it) }, { DetailField(labels.endPosition, formatSpan(entry.watched_seconds), it) }, stacked)
        DetailPair({ DetailField(labels.method, methodText, it) }, { DetailField(labels.endReason, endReason, it) }, stacked)
        if (video != null || audio != null) DetailPair({ DetailField(labels.video, video, it) }, { DetailField(labels.audio, audio, it) }, stacked)
        if (pausedText != null) DetailField(labels.paused, pausedText)
        DetailPair({ DetailField(labels.client, client, it) }, { DetailField(labels.platform, entry.device_platform, it) }, stacked)
        DetailField(labels.ip, ip)
        DetailField(labels.session, entry.session_id)
    }
}

private class HistoryLabels(
    val started: String, val ended: String, val watchTime: String, val endPosition: String, val paused: String,
    val method: String, val video: String, val audio: String, val endReason: String, val user: String, val profile: String,
    val client: String, val platform: String, val ip: String, val session: String,
)
