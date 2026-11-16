package com.bloemstudios.admin.streams

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

// Pure presentation rules for a live stream, ported from the web admin's
// adminActivityPresentation.ts so the app and the web name a session the same way. Nothing
// here touches Android or string resources: the screens map these results to wording.

/** The whole-session bucket (the server's effective_play_method): Direct play, Remux, Direct stream, Transcode. */
enum class StreamMethod { Direct, Remux, DirectStream, Transcode, Unknown }

/** One component's decision (the server's video_decision / audio_decision). */
enum class StreamDecision { Direct, Copy, Transcode, Unknown }

/** The web's normalizeStreamDecision: remux counts as copy; hls and anything else is unknown. */
fun streamDecisionOf(raw: String?): StreamDecision = when (raw?.trim()) {
    // "direct play" is how this app's earlier fixtures and its first Now Playing spelled it.
    "direct", "direct play" -> StreamDecision.Direct
    "copy", "remux" -> StreamDecision.Copy
    "transcode" -> StreamDecision.Transcode
    else -> StreamDecision.Unknown
}

fun videoDecisionOf(session: AdminSession): StreamDecision =
    streamDecisionOf(session.video_decision?.takeIf(String::isNotBlank) ?: session.play_method)

fun audioDecisionOf(session: AdminSession): StreamDecision = streamDecisionOf(
    session.audio_decision?.takeIf(String::isNotBlank)
        ?: if (session.transcode_audio) "transcode" else session.play_method,
)

/**
 * The server's effective_play_method when it sends one (its closed vocabulary is direct, remux,
 * direct_stream — "audio" in older builds — and transcode); otherwise the web's local reduction
 * of the two component decisions.
 */
fun streamMethodOf(session: AdminSession): StreamMethod {
    session.effective_play_method?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        return when (value) {
            "direct" -> StreamMethod.Direct
            "remux" -> StreamMethod.Remux
            "direct_stream", "audio" -> StreamMethod.DirectStream
            "transcode" -> StreamMethod.Transcode
            else -> StreamMethod.Unknown
        }
    }
    val video = videoDecisionOf(session)
    val audio = audioDecisionOf(session)
    return when {
        video == StreamDecision.Transcode -> StreamMethod.Transcode
        audio == StreamDecision.Transcode && video != StreamDecision.Unknown -> StreamMethod.DirectStream
        video == StreamDecision.Direct && audio == StreamDecision.Direct -> StreamMethod.Direct
        video == StreamDecision.Copy || audio == StreamDecision.Copy -> StreamMethod.Remux
        else -> StreamMethod.Unknown
    }
}

// ---- Titles and people -------------------------------------------------------------------

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun isEpisode(session: AdminSession): Boolean =
    session.series_name.clean() != null && session.season_number != null && session.episode_number != null

/** "Series · S2E3 · Episode name" for an episode, the media title for anything else. */
fun streamTitle(session: AdminSession): String {
    if (!isEpisode(session)) return session.media_title
    val series = session.series_name.clean()
    val name = session.episode_name.clean()
        ?: session.media_title.clean()?.takeIf { it != series }
    return listOfNotNull(series, "S${session.season_number}E${session.episode_number}", name).joinToString(" · ")
}

/** "user · profile"; the profile is left out when the server did not name it or it repeats the user. */
fun streamUserLabel(session: AdminSession): String {
    val profile = session.profile_name.clean()?.takeIf { !it.equals(session.username, ignoreCase = true) }
    return listOfNotNull(session.username.clean(), profile).joinToString(" · ")
}

/** The web's getSessionClientLabel: the server's own label, else name and version, else the name. */
fun streamClientLabel(session: AdminSession): String? {
    session.client_label.clean()?.let { return it }
    val name = session.client_name.clean()
    val version = session.client_version.clean()
    return if (name != null && version != null) "$name $version" else name
}

/** The exact client identity for the details: version, build and channel when the server sent them. */
fun streamClientLabelFull(session: AdminSession): String? =
    session.client_label_full.clean() ?: streamClientLabel(session)

private val PLATFORM_LABELS = mapOf(
    "android" to "Android", "ios" to "iOS", "ipados" to "iPadOS", "tvos" to "tvOS", "macos" to "macOS",
    "windows" to "Windows", "linux" to "Linux", "web" to "Web", "androidtv" to "Android TV", "tizen" to "Tizen",
    "webos" to "webOS", "roku" to "Roku",
)

/** The device the player declared, as a readable platform name. */
fun streamDeviceLabel(session: AdminSession): String? {
    val platform = session.device_platform.clean() ?: return null
    return PLATFORM_LABELS[platform.lowercase(Locale.ROOT)] ?: platform
}

/** "Client app · device", whichever the player reported. */
fun streamClientLine(session: AdminSession): String? =
    listOfNotNull(streamClientLabel(session), streamDeviceLabel(session)).joinToString(" · ").ifEmpty { null }

// ---- Time ---------------------------------------------------------------------------------

/** 754 → "12:34"; 3723 → "1:02:03". Negative and non-finite values read as zero. */
fun formatPlaybackClock(seconds: Double): String {
    val total = if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) 0L else floor(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs)
    else "%d:%02d".format(Locale.ROOT, minutes, secs)
}

/** Time left, compact: "1h 5m", "32m", "45s". Seconds round up, so a stream with a moment left does not read "0s". */
fun formatRemainingTime(seconds: Double): String {
    val total = if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0) 0L else ceil(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        total >= 60 -> "${minutes}m"
        else -> "${total}s"
    }
}

/** The seconds left, or null when the file's length is unknown. */
fun remainingSeconds(position: Double, duration: Double?): Double? {
    val length = duration?.takeIf { it > 0.0 } ?: return null
    return (length - position).coerceAtLeast(0.0)
}

/**
 * Where playback is now: the last reported position plus the time since that report while it
 * plays, held at the report while it is paused, never past the end of the file.
 */
fun projectedPosition(position: Double, duration: Double?, paused: Boolean, elapsedMillis: Long): Double {
    val base = if (position.isNaN() || position < 0) 0.0 else position
    val moved = if (paused || elapsedMillis <= 0) base else base + elapsedMillis / 1000.0
    val length = duration?.takeIf { it > 0.0 } ?: return moved
    return moved.coerceAtMost(length)
}

/** 0..1 progress, or null when the file's length is unknown. */
fun progressFraction(position: Double, duration: Double?): Float? {
    val length = duration?.takeIf { it > 0.0 } ?: return null
    return (position / length).toFloat().coerceIn(0f, 1f)
}

/** A server timestamp in the device's zone ("13 Nov 2026, 14:02"), or null if absent or unparseable. */
fun formatSessionInstant(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val instant = runCatching { Instant.parse(iso.clean() ?: return null) }.getOrNull() ?: return null
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(zone).format(instant)
}

// ---- Media formatting ---------------------------------------------------------------------

private val CODEC_LABELS = mapOf(
    "aac" to "AAC", "ac3" to "AC3", "av1" to "AV1", "dts" to "DTS", "dtshd" to "DTS-HD", "eac3" to "EAC3",
    "flac" to "FLAC", "h264" to "H.264", "hevc" to "HEVC", "mp3" to "MP3", "opus" to "Opus", "truehd" to "TrueHD",
    "h265" to "HEVC", "avc" to "H.264",
)

/** The web's formatCodecLabel: known codecs by name, any other upper-cased; null when blank. */
fun codecLabel(codec: String?): String? {
    val value = codec.clean() ?: return null
    return CODEC_LABELS[value.lowercase(Locale.ROOT)] ?: value.uppercase(Locale.ROOT)
}

private val HEIGHT_P = Regex("^(\\d{3,4})p$", RegexOption.IGNORE_CASE)
private val WIDTH_X_HEIGHT = Regex("^(\\d{3,5})x(\\d{3,5})$", RegexOption.IGNORE_CASE)

private fun heightLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    else -> "${height}p"
}

/** "2160p" and "3840x2160" read "4K", "1920x1080" and "1080p" read "1080p"; anything else passes through. */
fun resolutionLabel(resolution: String?): String? {
    val value = resolution.clean() ?: return null
    if (value.equals("4k", ignoreCase = true) || value.equals("uhd", ignoreCase = true)) return "4K"
    if (value.equals("8k", ignoreCase = true)) return "8K"
    HEIGHT_P.matchEntire(value)?.let { return heightLabel(it.groupValues[1].toInt()) }
    WIDTH_X_HEIGHT.matchEntire(value)?.let { match ->
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        // A 3840x1600 file is a 4K title; the catalog sizes by the wider edge too.
        return if (width >= 7680) "8K" else if (width >= 3840) "4K" else heightLabel(height)
    }
    return value
}

/** 1 → "1.0", 2 → "2.0", 6 → "5.1", 8 → "7.1", other counts "Nch"; null when unknown. */
fun channelLayoutLabel(channels: Int?): String? = when {
    channels == null || channels <= 0 -> null
    channels == 1 -> "1.0"
    channels == 2 -> "2.0"
    channels == 6 -> "5.1"
    channels == 8 -> "7.1"
    else -> "${channels}ch"
}

/** "8.2 Mbps" from 1,000 kbps up, "850 kbps" below; null for a missing or non-positive rate. */
fun formatBitrate(kbps: Int?, locale: Locale = Locale.getDefault()): String? {
    if (kbps == null || kbps <= 0) return null
    return if (kbps >= 1000) "%.1f Mbps".format(locale, kbps / 1000.0) else "$kbps kbps"
}

private fun join(vararg parts: String?): String? = parts.filterNotNull().joinToString(" ").ifEmpty { null }

/** What the source file holds and what is delivered: "HEVC 4K" and, when it is converted, "H.264 1080p". */
data class MediaFlow(val source: String?, val target: String?)

/**
 * The web keeps the source's size class when a transcode targets the source's own height (a
 * 3840x1600 file is "2160p", its full-height output "1600p"), so a full-size output does not
 * read as a downscale.
 */
private fun deliveredResolution(session: AdminSession): String? {
    val target = session.target_resolution.clean() ?: return null
    val targetHeight = HEIGHT_P.matchEntire(target)?.groupValues?.get(1)?.toIntOrNull()
    val sourceHeight = session.source_video_height
    if (sourceHeight != null && sourceHeight > 0 && targetHeight == sourceHeight) {
        resolutionLabel(session.source_video_resolution)?.let { return it }
    }
    return resolutionLabel(target)
}

fun videoFlow(session: AdminSession): MediaFlow {
    val source = join(codecLabel(session.source_video_codec), resolutionLabel(session.source_video_resolution))
    val target = if (videoDecisionOf(session) == StreamDecision.Transcode) {
        join(codecLabel(session.target_video_codec), deliveredResolution(session))
    } else null
    return MediaFlow(source, target)
}

/** The target channel layout is shown only when the server reported it; the source layout is never reused. */
fun audioFlow(session: AdminSession): MediaFlow {
    val source = join(codecLabel(session.source_audio_codec), channelLayoutLabel(session.source_audio_channels))
    val target = if (audioDecisionOf(session) == StreamDecision.Transcode) {
        join(codecLabel(session.target_audio_codec ?: "aac"), channelLayoutLabel(session.target_audio_channels))
    } else null
    return MediaFlow(source, target)
}

/** "Source → target" when both are known, otherwise whichever one is. */
fun MediaFlow.text(arrow: String = " → "): String? =
    if (source != null && target != null) "$source$arrow$target" else target ?: source

/** The track's language as a readable name ("en" → "English"); the raw tag when the device cannot name it. */
fun audioLanguageLabel(tag: String?, locale: Locale = Locale.getDefault()): String? {
    val value = tag.clean() ?: return null
    val parsed = Locale.forLanguageTag(value.replace('_', '-'))
    val name = parsed.getDisplayLanguage(locale).trim()
    // A tag the device cannot name displays as its own code: keep the tag the server sent.
    return name.takeIf { it.isNotEmpty() && !it.equals(parsed.language, ignoreCase = true) } ?: value
}

// ---- Transcode details -----------------------------------------------------------------------

/** What does the video work (the web's formatTranscodeModeSummary). */
sealed interface TranscodeMode {
    data class Hardware(val accel: String) : TranscodeMode
    data object Software : TranscodeMode
    data object Pending : TranscodeMode
    data object Unknown : TranscodeMode
    /** Only the audio is re-encoded, so there is no video encoder mode to name. */
    data object AudioOnly : TranscodeMode
}

/** Null when nothing is being transcoded. */
fun transcodeModeOf(session: AdminSession): TranscodeMode? {
    val video = videoDecisionOf(session) == StreamDecision.Transcode
    val audio = audioDecisionOf(session) == StreamDecision.Transcode
    if (!video && !audio) return null
    if (!video) return TranscodeMode.AudioOnly
    return when (val accel = session.transcode_hw_accel?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> TranscodeMode.Unknown
        "none" -> TranscodeMode.Software
        "auto" -> TranscodeMode.Pending
        "qsv" -> TranscodeMode.Hardware("QSV")
        "vaapi" -> TranscodeMode.Hardware("VAAPI")
        "videotoolbox" -> TranscodeMode.Hardware("VideoToolbox")
        else -> TranscodeMode.Hardware(accel.uppercase(Locale.ROOT))
    }
}

enum class ToneMap { Hardware, Software }

/** HDR-to-SDR tone mapping, only for a transcoded video and only when the server confirmed the executor. */
fun toneMapOf(session: AdminSession): ToneMap? {
    if (videoDecisionOf(session) != StreamDecision.Transcode) return null
    return when (session.tone_map_mode?.trim()?.lowercase(Locale.ROOT)) {
        "hardware" -> ToneMap.Hardware
        "software" -> ToneMap.Software
        else -> null
    }
}

// ---- Delivery and route -----------------------------------------------------------------------

/** The source container, upper-cased ("MKV"). */
fun sourceContainerLabel(session: AdminSession): String? = session.source_container.clean()?.uppercase(Locale.ROOT)

/**
 * The container the stream is delivered in: the source's own for a direct play, else what the
 * serving node reported ("fMP4", "MPEG-TS" or its upper-cased name), with "HLS" when it is
 * served over that protocol. Null when nothing was reported.
 */
fun deliveredContainerLabel(session: AdminSession): String? {
    if (session.play_method.trim() == "direct") return sourceContainerLabel(session)
    val container = session.output_container.clean()?.lowercase(Locale.ROOT)?.let {
        when (it) {
            "fmp4" -> "fMP4"
            "mpegts" -> "MPEG-TS"
            else -> it.uppercase(Locale.ROOT)
        }
    }
    val hls = session.output_protocol.clean() == "hls"
    return when {
        container != null && hls -> "HLS · $container"
        container != null -> container
        hls -> "HLS"
        else -> null
    }
}

enum class RouteRole { Transcode, Proxy, Server, Node }

/** One node a stream passes through; a null name means the local server. */
data class RouteNode(val role: RouteRole, val name: String?)

/** The nodes that serve the stream, work first, following the web's getSessionRouteNodes. */
fun routeNodesOf(session: AdminSession): List<RouteNode> {
    val execution = session.routing_execution.clean()
    val egress = session.routing_egress.clean()
    fun server(): RouteNode {
        val reported = session.reporting_node.clean()
        return RouteNode(RouteRole.Server, reported?.takeUnless { it.equals("local", ignoreCase = true) })
    }
    if (session.routing_workload.clean() == null && execution == null && egress == null) {
        val reported = session.node_display_name.clean() ?: session.reporting_node.clean()
        return listOf(RouteNode(RouteRole.Node, reported?.takeUnless { it.equals("local", ignoreCase = true) }))
    }
    val nodes = mutableListOf<RouteNode>()
    val executionName = session.routing_execution_node_name.clean() ?: session.node_display_name.clean()
    if (execution == "transcode") nodes += RouteNode(RouteRole.Transcode, executionName)
    else if (execution == "proxy") nodes += RouteNode(RouteRole.Proxy, executionName)
    if (egress == "proxy") {
        val egressNode = RouteNode(RouteRole.Proxy, session.routing_egress_node_name.clean())
        if (egressNode !in nodes) nodes += egressNode
    }
    if (egress == "api" || (execution == "api" && nodes.isEmpty())) nodes += server()
    return nodes.ifEmpty { listOf(server()) }
}
