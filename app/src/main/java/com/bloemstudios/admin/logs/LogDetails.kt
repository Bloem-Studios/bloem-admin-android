package com.bloemstudios.admin.logs

import com.bloemstudios.admin.audit.AuditLogEntry
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// What a log row and its detail sheet read out of an entry, ported from the web's AdminLogs.tsx and AdminLogs.format.ts so
// the app and the web trace an incident the same way. A request's method, status, path and duration are not columns of the
// application log: the server records them in `attrs`, so they are read from there.

/** A string or number attribute as text; null when it is missing, empty, or any other kind of value. */
fun OperationalLogEntry.attrText(key: String): String? {
    val value = attrs?.get(key) as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    if (value.isString) return value.content.takeIf(String::isNotEmpty)
    return value.content.takeIf { it.toDoubleOrNull() != null }
}

/** The request's duration in milliseconds, as text ("45", "12.5"), from `duration_ms`. */
fun OperationalLogEntry.durationMs(): String? {
    val value = attrs?.get("duration_ms") as? JsonPrimitive ?: return null
    if (value.isString) return value.content.takeIf(String::isNotEmpty)
    val number = value.doubleOrNull ?: return null
    return if (number % 1.0 == 0.0) number.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, number)
}

fun OperationalLogEntry.clientIpOrAttr(): String? = client_ip?.takeIf(String::isNotBlank) ?: attrText("client_ip")
fun OperationalLogEntry.sessionOrAttr(): String? = session_id?.takeIf(String::isNotBlank) ?: attrText("session_id")
fun OperationalLogEntry.playbackSessionOrAttr(): String? = playback_session_id?.takeIf(String::isNotBlank) ?: attrText("playback_session_id")

/** An address without the prefix length the server may append ("203.0.113.7/32"). */
fun plainAddress(value: String): String = value.replace(Regex("/\\d+$"), "")

/** What a playback session left in the logs: how many lines, from when to when, and on which nodes. */
data class PlaybackSessionSummary(
    val appCount: Int,
    val ffmpegCount: Int,
    val auditCount: Int,
    val firstSeen: String?,
    val lastSeen: String?,
    val nodes: List<String>,
)

/** The web's PlaybackSessionSummary: counts over the rows loaded for [playbackSessionId], and the first and last time seen. */
fun summarizePlaybackSession(
    playbackSessionId: String,
    appRows: List<OperationalLogEntry>,
    auditRows: List<AuditLogEntry>,
): PlaybackSessionSummary {
    val app = appRows.filter { playbackSessionId.isBlank() || it.playback_session_id == playbackSessionId }
    val audit = auditRows.filter { playbackSessionId.isBlank() || it.playback_session_id == playbackSessionId }
    val times = (app.map { it.timestamp } + audit.map { it.timestamp }).sorted()
    val nodes = (app.mapNotNull { it.node_id } + audit.mapNotNull { it.node_id }).filter(String::isNotBlank).toSortedSet().toList()
    return PlaybackSessionSummary(
        appCount = app.size,
        ffmpegCount = app.count { it.component == FFMPEG_COMPONENT },
        auditCount = audit.size,
        firstSeen = times.firstOrNull(),
        lastSeen = times.lastOrNull(),
        nodes = nodes,
    )
}

const val FFMPEG_COMPONENT = "ffmpeg"

/** The level choices; [wire] is what the server's `level` filter takes (it accepts a comma-separated list). */
enum class LogLevelChoice(val wire: String?) {
    All(null),
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error"),
    WarnError("error,warn"),
}

/**
 * Which log lines to list. [level] is the server's wire value. [component], [requestId] and [playbackSessionId] are the
 * narrowing filters the web offers beside level and message text.
 */
data class LogFilter(
    val level: String? = null,
    val q: String? = null,
    val component: String? = null,
    val requestId: String? = null,
    val playbackSessionId: String? = null,
) {
    /** How many of the filters beyond level and text are set: what the Filters button counts. */
    val extraFilterCount: Int get() = listOf(component, requestId, playbackSessionId).count { !it.isNullOrBlank() }

    fun normalized(): LogFilter = LogFilter(level.clean(), q.clean(), component.clean(), requestId.clean(), playbackSessionId.clean())

    private fun String?.clean() = this?.trim()?.takeIf(String::isNotEmpty)
}

/** The components the web suggests (web adminLogsFilters.ts); the server's set is not closed, so a typed one is also kept. */
val LOG_COMPONENT_SUGGESTIONS: List<String> = listOf(
    "access", "activitylog", "adminjob", "ai", "api", "app", "audiobooks", "auth", "autoscan", "catalog", "chapterthumbs",
    "diagnostics", "downloads", "ebooks", "ffmpeg", "historyimport", "jellycompat", "libraryingest", "manga", "metadata",
    "nodeconfig", "nodepool", "noderecipe", "nodesessions", "notifications", "opslog", "playback", "plugins", "policy",
    "proxy", "ratelimit", "recommendations", "requests", "scanner", "scanqueue", "sections", "settings", "taskmanager",
    "telemetry", "transcodenode", "watchlist", "watchsync", "webhook_sync", "webhooksync", "worker",
)

/** [LOG_COMPONENT_SUGGESTIONS] plus the component already chosen when it is not one of them. */
fun componentChoices(current: String?): List<String> =
    if (current.isNullOrBlank() || current in LOG_COMPONENT_SUGGESTIONS) LOG_COMPONENT_SUGGESTIONS
    else (LOG_COMPONENT_SUGGESTIONS + current).sorted()

/** Attributes as indented JSON for the detail sheet. */
fun JsonObject?.prettyAttributes(): String =
    kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), this ?: JsonObject(emptyMap()))
