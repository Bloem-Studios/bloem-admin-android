package com.bloemstudios.admin.activity

import com.bloemstudios.admin.streams.StreamMethod
import java.time.Duration
import java.time.Instant

// Pure presentation rules for a playback history row, ported from the web's AdminPlaybackHistory.tsx so the app and the
// web name a play the same way. Nothing here touches Android or string resources.

/** "S02E04" for an episode; null when either number is unknown. */
fun episodeCode(season: Int?, episode: Int?): String? =
    if (season == null || episode == null) null else "S%02dE%02d".format(season, episode)

/** The row's headline and, for an episode, the line under it. */
data class HistoryTitle(val title: String, val detail: String?)

/**
 * An episode leads with its series title and puts "S02E04 · Episode title" second; anything else keeps its own title,
 * or the catalog id when the item has left the catalog. A pilot named after its show does not repeat the series title.
 */
fun historyTitle(entry: PlaybackHistoryEntry): HistoryTitle {
    if (entry.series_title.isNotBlank()) {
        val episodeTitle = entry.media_title.takeUnless { it == entry.series_title }.orEmpty()
        val detail = listOfNotNull(episodeCode(entry.season_number, entry.episode_number), episodeTitle.takeIf(String::isNotBlank))
            .joinToString(" · ")
        return HistoryTitle(entry.series_title, detail.takeIf(String::isNotEmpty))
    }
    return HistoryTitle(entry.media_title.ifBlank { entry.media_item_id }, null)
}

/** How long a play ran. [estimated] is true for a play recorded before watch time was measured. */
data class WatchTime(val seconds: Double, val estimated: Boolean)

/**
 * The measured watch time, or for an unmeasured play the server's estimate as the web draws it: the end position capped
 * at the time between start and end.
 */
fun watchTime(entry: PlaybackHistoryEntry): WatchTime {
    entry.played_seconds?.let { return WatchTime(it.coerceAtLeast(0.0), estimated = false) }
    val started = runCatching { Instant.parse(entry.started_at) }.getOrNull()
    val ended = entry.ended_at?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val capped = if (started != null && ended != null) {
        minOf(entry.watched_seconds, Duration.between(started, ended).toMillis().coerceAtLeast(0) / 1000.0)
    } else {
        entry.watched_seconds
    }
    return WatchTime(capped.coerceAtLeast(0.0), estimated = true)
}

/** The play method the server recorded, in the stream palette ("audio" is the older spelling of direct stream). */
fun historyMethod(playMethod: String): StreamMethod = when (playMethod.trim()) {
    "direct" -> StreamMethod.Direct
    "remux" -> StreamMethod.Remux
    "direct_stream", "audio" -> StreamMethod.DirectStream
    "transcode" -> StreamMethod.Transcode
    else -> StreamMethod.Unknown
}

/** "1h 12m", "12m 5s" or "42s"; "0m" when the length is unknown or empty, as the web writes it. */
fun formatSpan(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "0m"
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val rest = total % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${rest}s"
        else -> "${rest}s"
    }
}

/**
 * "maria · Living room". The profile is left out when it repeats the account or was not recorded; an account that no
 * longer exists is shown by [fallback] applied to its [userId].
 */
fun historyPeopleLine(username: String, profileName: String, userId: String = "", fallback: (String) -> String = { it }): String {
    val who = username.ifBlank { fallback(userId) }
    return if (profileName.isBlank() || profileName == who) who else "$who · $profileName"
}
