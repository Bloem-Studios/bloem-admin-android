package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.serverconfig.ServerStatus
import com.bloemstudios.admin.users.AdminUser
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

// The endpoints behind the web dashboard's widgets, other than /admin/stats and
// /admin/system/resources (see AdminStats.kt). Each model keeps only the fields a
// dashboard section shows; the rest of the response is ignored on decode.

/** GET /api/v2/admin/system/build. */
@Serializable
data class BuildInfo(
    val available: Boolean = true,
    val build_number: Int = 0,
    val dirty: Boolean = false,
    val display: String = "",
    val revision: String = "",
)

/** One row of GET /api/v2/admin/nodes. */
@Serializable
data class DashboardNode(
    val id: String,
    val name: String = "",
    val enabled: Boolean,
    val healthy: Boolean,
)

/** One row of GET /api/v2/libraries. */
@Serializable
data class DashboardLibrary(
    val id: String,
    val name: String,
    val type: String = "",
    val paths: List<String> = emptyList(),
    val enabled: Boolean = true,
    val last_scanned_at: String? = null,
)

/** One hour (or day, past a two-day window) that saw a session. Hours without one are absent. */
@Serializable
data class PlaybackBucket(val hour: String, val direct: Int = 0, val remux: Int = 0, val transcode: Int = 0)

@Serializable
data class PlaybackReliability(
    val sessions_started: Int = 0,
    val transcode_starts: Int = 0,
    val finalized_sessions: Int = 0,
    val completed_sessions: Int = 0,
    val completion_rate: Double = 0.0,
    val unique_profiles: Int = 0,
)

/** GET /api/v2/admin/stats/playback-activity. */
@Serializable
data class PlaybackActivity(
    val hours: Int = 24,
    val bucket_seconds: Int = 3600,
    /** The window on the server's clock; the chart anchors its bucket grid on [to] so a phone clock cannot misplace the newest bucket. */
    val from: String? = null,
    val to: String? = null,
    val buckets: List<PlaybackBucket> = emptyList(),
    val reliability: PlaybackReliability = PlaybackReliability(),
    val profiles_active_24h: Int,
) {
    val directStarts: Int get() = buckets.sumOf { it.direct }
    val remuxStarts: Int get() = buckets.sumOf { it.remux }
    val transcodeStarts: Int get() = buckets.sumOf { it.transcode }
    val totalStarts: Int get() = directStarts + remuxStarts + transcodeStarts
}

@Serializable
data class TimeseriesPoint(
    val t: String,
    val streams: Int = 0,
    val egress_kbps: Double = 0.0,
    /** The file-transfer part of [egress_kbps]; 0 on samples written before the server measured it. */
    val download_egress_kbps: Double = 0.0,
    /** [streams] split by how each is delivered, from the sample's peak minute. All 0 on a server that predates the split. */
    val direct: Int = 0,
    val remux: Int = 0,
    val transcode: Int = 0,
)

/**
 * GET /api/v2/admin/stats/timeseries. Minutes the sampler missed are absent, not zero. A wide window comes
 * back in wider buckets ([resolution_seconds]), each holding its peak minute.
 */
@Serializable
data class Timeseries(
    val resolution_seconds: Int = 60,
    val from: String? = null,
    val to: String? = null,
    val oldest_sample_at: String? = null,
    /** Bytes served over the whole window, integrated by the server from every sampled minute. Absent on older servers. */
    val egress_bytes: Double? = null,
    val points: List<TimeseriesPoint> = emptyList(),
) {
    val peakMbps: Double? get() = points.maxOfOrNull { it.egress_kbps }?.div(KBPS_PER_MBPS)
    val averageMbps: Double? get() = points.takeIf { it.isNotEmpty() }?.map { it.egress_kbps }?.average()?.div(KBPS_PER_MBPS)

    /** The newest sample's egress, or null when it is older than [maxAge]: a stale rate must not pass as the current one. */
    fun freshEgressKbps(now: Instant, maxAge: Duration = EGRESS_SAMPLE_MAX_AGE): Double? {
        val latest = points.lastOrNull() ?: return null
        val sampledAt = runCatching { Instant.parse(latest.t) }.getOrNull() ?: return null
        return latest.egress_kbps.takeIf { Duration.between(sampledAt, now) <= maxAge }
    }
}

@Serializable
data class TopTitle(
    val media_item_id: String = "",
    val title: String = "",
    val media_type: String = "",
    val plays: Int = 0,
    val total_seconds: Double = 0.0,
)

@Serializable
data class TopProfile(
    val user_id: String = "",
    val username: String = "",
    val profile_id: String = "",
    val profile_name: String = "",
    val plays: Int = 0,
    val total_seconds: Double = 0.0,
)

/** GET /api/v2/admin/stats/top-activity. Episodes are rolled up to their series. */
@Serializable
data class TopActivity(
    val days: Int = 7,
    val titles: List<TopTitle> = emptyList(),
    val profiles: List<TopProfile> = emptyList(),
)

/** `/admin/server/status` health: whether each dependency is configured before whether it is up. */
@Serializable
data class HealthComponent(val configured: Boolean, val ok: Boolean? = null, val latency_ms: Double? = null)

@Serializable
data class ServerHealth(
    val postgres: HealthComponent,
    val redis: HealthComponent,
    val errors_24h: Int = 0,
    val warnings_24h: Int = 0,
)

/** One independently loaded dashboard section: the last good value plus the error of the latest failed refresh, if any. */
data class SectionState<out T>(val value: T? = null, val error: ErrorText? = null) {
    /** Loading is neither: a section with no value and no error has nothing to say yet. */
    val hasContent: Boolean get() = value != null || error != null
}

/** Everything the dashboard shows beyond the stats, host sample and live sessions. */
data class DashboardInsights(
    val status: SectionState<ServerStatus> = SectionState(),
    val build: SectionState<BuildInfo> = SectionState(),
    val nodes: SectionState<List<DashboardNode>> = SectionState(),
    val activity: SectionState<PlaybackActivity> = SectionState(),
    val egressNow: SectionState<Timeseries> = SectionState(),
    val egress24h: SectionState<Timeseries> = SectionState(),
    val top: SectionState<TopActivity> = SectionState(),
    val libraries: SectionState<List<DashboardLibrary>> = SectionState(),
    val users: SectionState<List<AdminUser>> = SectionState(),
    val recent: SectionState<List<PlaybackHistoryEntry>> = SectionState(),
    /** Playback activity for the windows other than the 24 hours in [activity], read only while a chart shows one. */
    val rangedActivity: Map<ChartRange, SectionState<PlaybackActivity>> = emptyMap(),
    /** The timeseries for the week and month windows; the hour is [egressNow] and the day is [egress24h], read only while a chart shows one. */
    val rangedSeries: Map<ChartRange, SectionState<Timeseries>> = emptyMap(),
) {
    /** What the playback chart shows for [range]: the 24-hour read every poll already makes, or the one read for that window. */
    fun activityFor(range: ChartRange): SectionState<PlaybackActivity> =
        if (range == ChartRange.Day) activity else rangedActivity[range] ?: SectionState()

    /** What the egress and concurrent-streams charts show for [range]; both plot the same endpoint. */
    fun seriesFor(range: ChartRange): SectionState<Timeseries> =
        when (range) {
            ChartRange.Hour -> egressNow
            ChartRange.Day -> egress24h
            else -> rangedSeries[range] ?: SectionState()
        }
}

/** The web's default arrangement (registry.tsx DEFAULT_LAYOUT_ORDER), in the order the dashboard renders it. */
enum class DashboardSection(val tag: String) {
    Tiles("dashboard-section-tiles"),
    Health("dashboard-section-health"),
    NowPlaying("dashboard-section-now-playing"),
    /** The one main chart: concurrent streams split by play method. */
    ConcurrentStreams("dashboard-section-concurrent-streams"),
    PlaybackActivity("dashboard-section-playback-activity"),
    Egress("dashboard-section-egress"),
    TopTitles("dashboard-section-top-titles"),
    TopProfiles("dashboard-section-top-profiles"),
    Libraries("dashboard-section-libraries"),
    Users("dashboard-section-users"),
    RecentActivity("dashboard-section-recent-activity"),
}

/**
 * The sections that have something to say, in the web's order. Now playing is always present
 * (it carries the loading, stale and idle states); every other section appears once it has a value or a failure.
 */
fun visibleDashboardSections(hasTiles: Boolean, hasHealth: Boolean, insights: DashboardInsights): List<DashboardSection> =
    DashboardSection.entries.filter { section ->
        when (section) {
            DashboardSection.Tiles -> hasTiles
            DashboardSection.Health -> hasHealth
            DashboardSection.NowPlaying -> true
            DashboardSection.PlaybackActivity -> insights.activity.hasContent
            DashboardSection.Egress, DashboardSection.ConcurrentStreams -> insights.egress24h.hasContent
            DashboardSection.TopTitles, DashboardSection.TopProfiles -> insights.top.hasContent
            DashboardSection.Libraries -> insights.libraries.hasContent
            DashboardSection.Users -> insights.users.hasContent
            DashboardSection.RecentActivity -> insights.recent.hasContent
        }
    }

enum class DashboardTileId { ActiveStreams, EgressNow, ProfilesActive, Movies, Shows, Storage }

data class DashboardTile(val id: DashboardTileId, val label: String, val value: String, val detail: String?, val live: Boolean = false)

/** The words and units the tiles need, so the tile logic stays free of Android resources. */
internal interface TileText {
    fun label(id: DashboardTileId): String
    fun sessions(count: Int): String
    fun files(count: Int): String
    fun profilesDetail(): String
    fun egressDetail(): String
    fun storage(bytes: Long): String
}

/**
 * The web's number tiles in its order. A tile whose data has not arrived (or, for egress, whose sample is
 * stale) is left out rather than shown as a dash.
 */
internal fun dashboardTiles(
    sessionCount: Int?,
    stats: AdminStats?,
    activity: PlaybackActivity?,
    egressKbps: Double?,
    text: TileText,
    locale: Locale = Locale.getDefault(),
): List<DashboardTile> {
    val number = NumberFormat.getIntegerInstance(locale)
    return buildList {
        if (sessionCount != null) add(DashboardTile(DashboardTileId.ActiveStreams,
            text.label(DashboardTileId.ActiveStreams), number.format(sessionCount), text.sessions(sessionCount), live = sessionCount > 0))
        if (egressKbps != null) add(DashboardTile(DashboardTileId.EgressNow,
            text.label(DashboardTileId.EgressNow), formatMbps(egressKbps / KBPS_PER_MBPS, locale), text.egressDetail()))
        if (activity != null) add(DashboardTile(DashboardTileId.ProfilesActive,
            text.label(DashboardTileId.ProfilesActive), number.format(activity.profiles_active_24h), text.profilesDetail()))
        if (stats != null) {
            add(DashboardTile(DashboardTileId.Movies, text.label(DashboardTileId.Movies),
                number.format(stats.total_movies), text.files(stats.total_movie_files)))
            add(DashboardTile(DashboardTileId.Shows, text.label(DashboardTileId.Shows),
                number.format(stats.total_shows), text.files(stats.total_show_files)))
            add(DashboardTile(DashboardTileId.Storage, text.label(DashboardTileId.Storage),
                text.storage(stats.total_storage_bytes), text.files(stats.total_files)))
        }
    }
}

/** Egress rate; small rates keep one decimal so a trickle does not round to a flat "0 Mbps" and read as idle. */
fun formatMbps(mbps: Double, locale: Locale = Locale.getDefault()): String =
    if (mbps.isFinite()) "${formatMbpsValue(mbps, locale)} Mbps" else "—"

/** Watch time for the top lists: sub-hour totals stay in minutes so a short session does not collapse to "0.0h". */
fun formatWatchTime(totalSeconds: Double, locale: Locale = Locale.getDefault()): String {
    if (!totalSeconds.isFinite() || totalSeconds <= 0) return "0m"
    if (totalSeconds < 3600) return "${maxOf(1, Math.round(totalSeconds / 60).toInt())}m"
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return "${format.format(totalSeconds / 3600)}h"
}

/** Uptime at the coarsest unit that still says something useful: "3d 4h", not a seconds counter. */
fun formatUptime(startedAt: String?, now: Instant): String {
    val started = startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "—"
    val seconds = maxOf(0L, Duration.between(started, now).seconds)
    if (seconds < 86_400) return formatDuration(seconds)
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3600
    return if (hours == 0L) "${days}d" else "${days}d ${hours}h"
}

private fun formatDuration(totalSeconds: Long): String = when {
    totalSeconds < 60 -> "${totalSeconds}s"
    totalSeconds < 3600 -> {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
    }
    else -> {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
    }
}

/** Sub-10ms round trips keep their decimals; anything slower does not need them. */
fun formatLatency(latencyMs: Double, locale: Locale = Locale.getDefault()): String {
    if (!latencyMs.isFinite()) return "—"
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (latencyMs < 10) 2 else 0 }
    return "${format.format(latencyMs)} ms"
}

/** Most recently active first; accounts with no recorded activity sink to the bottom. */
fun usersByLastActive(users: List<AdminUser>): List<AdminUser> =
    users.sortedWith(
        compareByDescending<AdminUser> { user -> user.last_active_at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH }
            .thenBy { it.username.lowercase() }
    )

/** "Profile · account" when the account name adds anything; per profile, since several profiles share one login. */
fun topProfileLabel(profile: TopProfile): String {
    val account = profile.username.ifBlank { "User ${profile.user_id}" }
    val name = profile.profile_name.ifBlank { profile.profile_id.ifBlank { account } }
    return if (name == account) account else "$name · $account"
}

private const val KBPS_PER_MBPS = 1_000.0

/** The sampler writes one row a minute, so a sample older than two minutes means it is behind or stopped. */
private val EGRESS_SAMPLE_MAX_AGE: Duration = Duration.ofMinutes(2)
