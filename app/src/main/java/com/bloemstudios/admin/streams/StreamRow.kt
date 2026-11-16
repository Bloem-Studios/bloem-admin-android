package com.bloemstudios.admin.streams

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.tabular
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.LucideIcon
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.theme.LocalReducedMotion
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilMotionTokens
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A row's test tag is this plus its session id; its opened details carry [STREAM_DETAILS_TAG_PREFIX]. */
const val STREAM_ROW_TAG_PREFIX = "stream-row:"
const val STREAM_DETAILS_TAG_PREFIX = "stream-details:"

private const val TICK_MILLIS = 1_000L

/** Milliseconds on a monotonic clock. Tests provide their own to move a stream forward without waiting. */
val LocalStreamClock: ProvidableCompositionLocal<() -> Long> =
    staticCompositionLocalOf { { SystemClock.elapsedRealtime() } }

/**
 * One live stream, drawn as its signal path: where it comes from (source), how it is delivered (the playback
 * decision) and where it lands (client), with timecode progress and bitrate in tabular figures. Only a
 * transcode step is lit. Tapped, the row opens everything else support needs.
 *
 * The stream's own controls are the caller's: [actions] is drawn in the row's top corner (the pages
 * put their overflow menu there) and is not part of the tappable summary. [showClientIp] is for the
 * platform administrator only; an organization context must leave it off. [expandable] false makes
 * a plain summary with no details, for a preview that opens the full page on tap. [showRouting] false leaves out the
 * serving node and network, which describe the server and not an organization.
 */
@Composable
fun StreamRow(
    session: AdminSession,
    modifier: Modifier = Modifier,
    showClientIp: Boolean = false,
    pending: Boolean = false,
    expandable: Boolean = true,
    actions: (@Composable () -> Unit)? = null,
    showRouting: Boolean = true,
    /** The row opens with its details showing: a link (the widget) named this stream. */
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(session.session_id) { mutableStateOf(startExpanded) }
    val reducedMotion = LocalReducedMotion.current
    val shape = RoundedCornerShape(VeilShapes.CompactCardCorner)
    val facts = streamFacts(session)
    val description = streamRowDescription(session, facts)
    val stateText = stringResource(if (expanded) R.string.streams_row_state_expanded else R.string.streams_row_state_collapsed)
    val clickLabel = stringResource(if (expanded) R.string.streams_row_hide_details else R.string.streams_row_show_details)

    Box(
        modifier
            .fillMaxWidth()
            .testTag(STREAM_ROW_TAG_PREFIX + session.session_id)
            .clip(shape)
            .background(VeilColors.Card, shape)
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(tween(VeilMotionTokens().fastMillis, easing = VeilMotionTokens().ease))),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { expanded = !expanded }
                    else Modifier,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (expandable) stateDescription = stateText
                }
                .padding(VeilSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            // The corner button sits over the header's right edge, so the header leaves it room.
            Column(Modifier.padding(end = if (actions != null) VeilSpacing.MinimumTouchTarget - VeilSpacing.Small else 0.dp)) {
                Text(
                    streamTitle(session), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Text(streamUserLabel(session), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
            SignalPath(session, facts)
            PlaybackProgress(session, active = !reducedMotion)
            StatusLine(session, expandable = expandable, expanded = expanded)
            if (pending) Text(stringResource(R.string.action_in_progress), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
            if (expandable && expanded) StreamDetails(session, showClientIp, showRouting)
        }
        if (actions != null) Box(Modifier.align(Alignment.TopEnd)) { actions() }
    }
}

// ---- Facts the row shows, resolved to the user's language ------------------------------------

/** The strings a row and its description are built from, so both read the same. */
@androidx.compose.runtime.Immutable
internal data class StreamFacts(
    val method: StreamMethod,
    val methodLabel: String,
    val videoLine: String?,
    val audioLine: String?,
    val extrasLine: String?,
)

@Composable
internal fun methodLabel(method: StreamMethod): String = stringResource(method.labelRes)

@Composable
private fun decisionLabel(decision: StreamDecision): String? = when (decision) {
    StreamDecision.Direct -> stringResource(R.string.streams_decision_direct)
    StreamDecision.Copy -> stringResource(R.string.streams_decision_copy)
    StreamDecision.Transcode -> stringResource(R.string.streams_decision_transcode)
    StreamDecision.Unknown -> null
}

@Composable
private fun transcodeModeLabel(mode: TranscodeMode): String = when (mode) {
    is TranscodeMode.Hardware -> stringResource(R.string.streams_mode_hardware, mode.accel)
    TranscodeMode.Software -> stringResource(R.string.streams_mode_software)
    TranscodeMode.Pending -> stringResource(R.string.streams_mode_pending)
    TranscodeMode.Unknown -> stringResource(R.string.streams_mode_unknown)
    TranscodeMode.AudioOnly -> stringResource(R.string.streams_mode_audio_only)
}

@Composable
private fun toneMapLabel(toneMap: ToneMap): String =
    stringResource(if (toneMap == ToneMap.Hardware) R.string.streams_tone_map_hardware else R.string.streams_tone_map_software)

private fun List<String?>.dotted(): String? = filterNotNull().filter(String::isNotBlank).joinToString(" · ").ifEmpty { null }

@Composable
internal fun streamFacts(session: AdminSession): StreamFacts {
    val method = streamMethodOf(session)
    val videoText = listOf(decisionLabel(videoDecisionOf(session)), videoFlow(session).text()).dotted()
    val audioText = listOf(
        decisionLabel(audioDecisionOf(session)), audioFlow(session).text(), audioLanguageLabel(session.source_audio_language),
    ).dotted()
    val extras = listOf(
        transcodeModeOf(session)?.let { transcodeModeLabel(it) },
        toneMapOf(session)?.let { toneMapLabel(it) },
        formatBitrate(session.stream_bitrate_kbps),
    ).dotted()
    return StreamFacts(
        method = method,
        methodLabel = methodLabel(method),
        videoLine = videoText?.let { stringResource(R.string.streams_line_video, it) },
        audioLine = audioText?.let { stringResource(R.string.streams_line_audio, it) },
        extrasLine = extras,
    )
}

/**
 * What a screen reader says for the whole row. It uses the last reported position, not the ticking
 * one, so the description does not change under the reader every second.
 */
@Composable
private fun streamRowDescription(session: AdminSession, facts: StreamFacts): String {
    val state = stringResource(if (session.is_paused) R.string.status_paused else R.string.status_playing)
    val duration = session.file_duration?.takeIf { it > 0 }
    val progress = if (duration != null) {
        stringResource(
            R.string.streams_progress_description,
            formatPlaybackClock(session.position_seconds), formatPlaybackClock(duration),
            formatRemainingTime(remainingSeconds(session.position_seconds, duration) ?: 0.0),
        )
    } else stringResource(R.string.streams_progress_description_open, formatPlaybackClock(session.position_seconds))
    return listOfNotNull(
        streamTitle(session), streamUserLabel(session), streamClientLine(session), state, facts.methodLabel,
        progress, facts.videoLine, facts.audioLine, facts.extrasLine,
    ).filter(String::isNotBlank).joinToString(". ")
}

// ---- Pieces ---------------------------------------------------------------------------------

/** The web chart's color for how a stream is delivered: direct play, direct stream (a remux is one too), transcode. */
@Composable
internal fun StreamMethod.markColor(): Color = when (chartIndex) {
    0 -> VeilColors.PlayDirect
    1 -> VeilColors.PlayStream
    2 -> VeilColors.PlayTranscode
    else -> VeilColors.Muted
}

/**
 * Source, playback decision and client as three steps joined by a line. The line and the steps stay neutral;
 * a transcode lights its step and the line into it in amber, because a converted stream is the one a person
 * managing the server needs to see. The decision step carries a small key in the play method's chart color.
 */
@Composable
private fun SignalPath(session: AdminSession, facts: StreamFacts) {
    val transcode = facts.method == StreamMethod.Transcode
    val source = listOfNotNull(videoFlow(session).source, sourceContainerLabel(session)).joinToString(" · ")
        .ifEmpty { stringResource(R.string.streams_detail_unknown_source) }
    val client = streamClientLine(session) ?: stringResource(R.string.streams_path_unknown_client)
    val converted = if (transcode) {
        listOfNotNull(
            transcodeModeOf(session)?.let { transcodeModeLabel(it) },
            videoFlow(session).target?.let { stringResource(R.string.streams_path_output, it) },
        ).joinToString(" · ").ifEmpty { null }
    } else null
    if (LocalDensity.current.fontScale >= 1.3f) {
        // Large text: the path reads top to bottom.
        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            val full = Modifier.fillMaxWidth()
            PathStep(stringResource(R.string.streams_path_source), source, modifier = full)
            PathStep(
                stringResource(R.string.streams_path_playback), facts.methodLabel, modifier = full,
                detail = converted, lit = transcode, key = facts.method.markColor(),
            )
            PathStep(stringResource(R.string.streams_path_client), client, modifier = full)
        }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
            val weight = Modifier.weight(1f)
            PathStep(stringResource(R.string.streams_path_source), source, modifier = weight)
            PathLink(lit = transcode)
            PathStep(
                stringResource(R.string.streams_path_playback), facts.methodLabel, modifier = weight,
                detail = converted, lit = transcode, key = facts.method.markColor(),
            )
            PathLink(lit = transcode)
            PathStep(stringResource(R.string.streams_path_client), client, modifier = weight)
        }
    }
}

@Composable
private fun PathStep(
    caption: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    lit: Boolean = false,
    key: Color? = null,
) {
    val shape = RoundedCornerShape(VeilShapes.InnerCorner)
    Column(
        modifier
            .then(if (lit) Modifier.background(VeilColors.Raised, shape) else Modifier)
            .padding(horizontal = VeilSpacing.Tiny, vertical = VeilSpacing.Tiny),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.Top) {
            if (key != null) Box(Modifier.padding(top = VeilSpacing.Tiny).size(8.dp).background(key, CircleShape))
            Text(
                value, style = MaterialTheme.typography.bodySmall,
                color = if (lit) VeilColors.Warning else VeilColors.Text,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = if (lit) VeilColors.Warning else VeilColors.Muted, maxLines = 2)
        }
    }
}

/** The line between two steps, with an arrowhead; amber into a transcode. */
@Composable
private fun PathLink(lit: Boolean) {
    val color = if (lit) VeilColors.PlayTranscode else VeilColors.Outline
    Canvas(Modifier.width(16.dp).height(32.dp).padding(top = VeilSpacing.Small)) {
        val y = size.height / 2f + 2.dp.toPx()
        val stroke = 1.5.dp.toPx()
        drawLine(color, Offset(1.dp.toPx(), y), Offset(size.width - 1.dp.toPx(), y), strokeWidth = stroke)
        val head = 3.dp.toPx()
        drawLine(color, Offset(size.width - 1.dp.toPx(), y), Offset(size.width - 1.dp.toPx() - head, y - head), strokeWidth = stroke)
        drawLine(color, Offset(size.width - 1.dp.toPx(), y), Offset(size.width - 1.dp.toPx() - head, y + head), strokeWidth = stroke)
    }
}

/**
 * Playing or paused, quietly. A playing stream is live right now, so it carries the tally dot; a paused one is the
 * exception and is set in the warning color. The chevron is last.
 */
@Composable
private fun StatusLine(session: AdminSession, expandable: Boolean, expanded: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (session.is_paused) {
            StatusLabel(stringResource(R.string.status_paused), tone = StatusTone.Warning, dot = true)
        } else {
            StatusLabel(stringResource(R.string.status_playing), tone = StatusTone.Live)
        }
        if (expandable) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                // Decorative: the row announces its expanded state itself.
                LucideIcon(
                    if (expanded) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down,
                    contentDescription = null, tint = VeilColors.Muted,
                )
            }
        }
    }
}

/**
 * Timecode and bitrate. While it plays the numbers move once a second between polls, from the last
 * reported position; paused they stay put. With reduced motion nothing moves between polls.
 */
@Composable
private fun PlaybackProgress(session: AdminSession, active: Boolean) {
    val clock = LocalStreamClock.current
    val duration = session.file_duration?.takeIf { it > 0.0 }
    val ticking = active && !session.is_paused
    // A new report (a changed position or pause state) restarts the count from that moment.
    val anchor = remember(session.session_id, session.position_seconds, session.is_paused, session.updated_at) { clock() }
    var now by remember(session.session_id, session.position_seconds, session.is_paused, session.updated_at) { mutableLongStateOf(anchor) }
    // Only while the app is in front. This ticker does no reads, so unlike ForegroundEffect it needs no
    // switch to the main dispatcher: it stays on the composition's own one, whose clock tests control.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, anchor, ticking) {
        if (!ticking) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(TICK_MILLIS)
                now = clock()
            }
        }
    }
    val position = projectedPosition(session.position_seconds, duration, session.is_paused, now - anchor)
    val fraction = progressFraction(position, duration)
    val bitrate = formatBitrate(session.stream_bitrate_kbps)
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (session.is_paused) VeilColors.Warning else VeilColors.Text,
                trackColor = VeilColors.Raised,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(
                if (duration != null) stringResource(R.string.streams_time_progress, formatPlaybackClock(position), formatPlaybackClock(duration))
                else formatPlaybackClock(position),
                style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (bitrate != null) {
                Text(
                    bitrate, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun StreamDetails(session: AdminSession, showClientIp: Boolean, showRouting: Boolean) {
    Column(
        Modifier.fillMaxWidth().testTag(STREAM_DETAILS_TAG_PREFIX + session.session_id).padding(top = VeilSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        streamDetailRows(session, showClientIp, showRouting).forEach { (label, value) -> DetailLine(label, value) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(Modifier.fillMaxWidth().padding(top = VeilSpacing.Tiny)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            Text(value, style = MaterialTheme.typography.bodySmall, color = VeilColors.Text)
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodySmall, color = VeilColors.Text)
        }
    }
}

/** What a component's decision means in words, the web's formatVideoDetail / formatAudioDetail. */
@Composable
private fun componentDetail(decision: StreamDecision, target: String?, none: Int): String? = when (decision) {
    StreamDecision.Transcode -> target?.let { stringResource(R.string.streams_detail_output, it) } ?: stringResource(R.string.streams_detail_transcoding)
    StreamDecision.Copy -> stringResource(R.string.streams_detail_copied)
    StreamDecision.Direct -> stringResource(none)
    StreamDecision.Unknown -> null
}

/**
 * The details a row opens to, label and value, in the order support reads them. A row with nothing
 * to say is left out; the client address is there only when [showClientIp].
 */
@Composable
internal fun streamDetailRows(session: AdminSession, showClientIp: Boolean, showRouting: Boolean = true): List<Pair<String, String>> {
    val unknownSource = stringResource(R.string.streams_detail_unknown_source)
    val source = sourceContainerLabel(session)
    val delivered = deliveredContainerLabel(session)
    val container = when {
        delivered == null -> source
        source != null && delivered != source -> "$source → $delivered"
        else -> delivered
    }
    val video = videoFlow(session)
    val audio = audioFlow(session)
    val videoValue = listOf(
        video.source ?: unknownSource,
        componentDetail(videoDecisionOf(session), video.target, R.string.streams_detail_no_video_conversion),
    ).dotted()
    val audioValue = listOf(
        audio.source ?: unknownSource,
        componentDetail(audioDecisionOf(session), audio.target, R.string.streams_detail_no_audio_conversion),
    ).dotted()
    val track = listOf(session.source_audio_title?.trim(), audioLanguageLabel(session.source_audio_language)).dotted()
    val transcoder = listOf(transcodeModeOf(session)?.let { transcodeModeLabel(it) }, toneMapOf(session)?.let { toneMapLabel(it) }).dotted()
    val network = when (session.stream_location?.trim()?.lowercase()) {
        "local" -> stringResource(R.string.streams_network_local)
        "remote" -> stringResource(R.string.streams_network_remote)
        else -> null
    }
    val nodes = routeNodesOf(session).map { routeNodeLabel(it) }.joinToString(", ")
    val rows = listOf<Pair<String, String?>?>(
        stringResource(R.string.streams_detail_method) to methodLabel(streamMethodOf(session)),
        stringResource(R.string.streams_detail_video) to videoValue,
        stringResource(R.string.streams_detail_audio) to audioValue,
        stringResource(R.string.streams_detail_audio_track) to track,
        stringResource(R.string.streams_detail_transcoder) to transcoder,
        stringResource(R.string.streams_detail_container) to container,
        stringResource(R.string.streams_detail_source_bitrate) to formatBitrate(session.source_bitrate_kbps),
        stringResource(R.string.streams_detail_stream_bitrate) to formatBitrate(session.stream_bitrate_kbps),
        stringResource(R.string.streams_detail_client) to streamClientLabelFull(session),
        if (showRouting) stringResource(R.string.streams_detail_node) to nodes else null,
        if (showRouting) stringResource(R.string.streams_detail_network) to network else null,
        if (showClientIp) stringResource(R.string.streams_detail_client_ip) to session.client_ip?.trim() else null,
        stringResource(R.string.streams_detail_started) to formatSessionInstant(session.started_at),
        stringResource(R.string.streams_detail_updated) to formatSessionInstant(session.updated_at),
    )
    return rows.mapNotNull { row -> row?.second?.takeIf(String::isNotBlank)?.let { row.first to it } }
}

@Composable
private fun routeNodeLabel(node: RouteNode): String {
    val role = stringResource(
        when (node.role) {
            RouteRole.Transcode -> R.string.streams_node_role_transcode
            RouteRole.Proxy -> R.string.streams_node_role_proxy
            RouteRole.Server -> R.string.streams_node_role_server
            RouteRole.Node -> R.string.streams_node_role_node
        },
    )
    return stringResource(R.string.streams_node_named, role, node.name ?: stringResource(R.string.streams_node_local))
}
