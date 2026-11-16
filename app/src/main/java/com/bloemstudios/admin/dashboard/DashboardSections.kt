package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.ui.components.TextButton
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.realtime.SessionSourceStatus
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.ui.components.Stat
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.time.Instant

/** What one server's dashboard shows: the stats and host sample, the live sessions and everything in [insights]. */
internal class DashboardSectionsData(
    val stats: AdminStats?,
    val hostStats: SystemResources?,
    val hostErrorMessage: String?,
    val hostUnavailable: Boolean,
    val sessions: List<AdminSession>?,
    val sessionsErrorMessage: String?,
    val sourceState: RealtimeState,
    val sourceError: SessionFailure?,
    val insights: DashboardInsights,
    val onStreamsRequested: (() -> Unit)?,
    val onUsersRequested: (() -> Unit)? = null,
    /** Opens the Nodes page from the Nodes cell of Server health; null where the server has no such page. */
    val onNodesRequested: (() -> Unit)? = null,
    /** When the catalog numbers were sampled; shown under the tiles when the screen has no refresh banner of its own. */
    val sampledAt: String? = null,
    /** The window each trend chart shows and how to change it. Null: the sections keep the choice themselves (previews, tests). */
    val chartRanges: ChartRanges? = null,
    val onChartRange: ((ChartRange) -> Unit)? = null,
)

/** The health section shows while it has something to say, or is still waiting to hear; it hides only when the server has nothing to report. */
internal fun healthSectionVisible(
    insights: DashboardInsights, hostStats: SystemResources?, hostError: String?, hostUnavailable: Boolean,
): Boolean = insights.status.hasContent || hostStats?.system != null || hostError != null || !hostUnavailable

/**
 * One server's dashboard: number tiles, server health, now playing, the concurrent-streams chart (the one large chart,
 * split by play method), then the smaller playback activity and egress charts under the same range, top titles, most
 * active profiles, libraries, users, recent activity.
 */
@Composable
internal fun DashboardSections(data: DashboardSectionsData) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tileText = remember(context, configuration) { ContextTileText(context) }
    val insights = data.insights
    val tiles = dashboardTiles(
        data.sessions?.size, data.stats, insights.activity.value,
        insights.egressNow.value?.freshEgressKbps(Instant.now()), tileText,
    )
    val sections = visibleDashboardSections(
        tiles.isNotEmpty(),
        healthSectionVisible(insights, data.hostStats, data.hostErrorMessage, data.hostUnavailable),
        insights,
    )
    var localRanges by remember { mutableStateOf(ChartRanges()) }
    val ranges = data.chartRanges ?: localRanges
    // One range control moves every trend chart: the main chart's window is the dashboard's.
    val onRange: (ChartRange) -> Unit = data.onChartRange ?: { range -> localRanges = localRanges.all(range) }
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        sections.forEach { section ->
            when (section) {
                DashboardSection.Tiles -> TilesSection(tiles, data.sampledAt)
                DashboardSection.Health -> HealthSection(data)
                DashboardSection.NowPlaying -> NowPlayingSection(data)
                DashboardSection.ConcurrentStreams -> ConcurrentStreamsSection(insights, ranges.streams, onRange)
                DashboardSection.PlaybackActivity -> PlaybackActivitySection(insights, ranges.playback)
                DashboardSection.Egress -> EgressSection(insights, ranges.egress)
                DashboardSection.TopTitles -> TopTitlesSection(insights.top)
                DashboardSection.TopProfiles -> TopProfilesSection(insights.top)
                DashboardSection.Libraries -> LibrariesSection(insights.libraries)
                DashboardSection.Users -> UsersSection(insights.users, data.onUsersRequested)
                DashboardSection.RecentActivity -> RecentActivitySection(insights.recent)
            }
        }
    }
}

internal class ContextTileText(private val context: Context) : TileText {
    override fun label(id: DashboardTileId): String = context.getString(
        when (id) {
            DashboardTileId.ActiveStreams -> R.string.dashboard_active_streams
            DashboardTileId.EgressNow -> R.string.dashboard_tile_egress_now
            DashboardTileId.ProfilesActive -> R.string.dashboard_tile_profiles_24h
            DashboardTileId.Movies -> R.string.dashboard_movies
            DashboardTileId.Shows -> R.string.dashboard_shows
            DashboardTileId.Storage -> R.string.dashboard_total_storage
        }
    )
    override fun sessions(count: Int): String = context.resources.getQuantityString(R.plurals.dashboard_tile_sessions, count, count)
    override fun files(count: Int): String = context.resources.getQuantityString(R.plurals.dashboard_tile_files, count, count)
    override fun profilesDetail(): String = context.getString(R.string.dashboard_tile_profiles_detail)
    override fun egressDetail(): String = context.getString(R.string.dashboard_tile_egress_detail)
    override fun storage(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}

// ---- Number tiles ----

/**
 * Active streams and Egress now lead, side by side; the catalog counts (profiles, movies, shows, storage) follow as
 * one quiet line, so the two numbers that change by the minute are the two that stand out.
 */
@Composable
private fun TilesSection(tiles: List<DashboardTile>, sampledAt: String?) {
    val large = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val lead = tiles.filter { it.id == DashboardTileId.ActiveStreams || it.id == DashboardTileId.EgressNow }
    val catalog = tiles - lead.toSet()
    Column(Modifier.testTag(DashboardSection.Tiles.tag), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (lead.isNotEmpty()) {
            if (large) Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) { lead.forEach { LeadTile(it, Modifier.fillMaxWidth()) } }
            else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                lead.forEach { LeadTile(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(2 - lead.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (catalog.isNotEmpty()) CatalogLine(catalog, stacked = large)
        sampledAt?.let {
            Text(stringResource(R.string.dashboard_sampled_at, formatRelativeTime(it)),
                style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }
}

@Composable
private fun LeadTile(tile: DashboardTile, modifier: Modifier) {
    val description = stringResource(R.string.dashboard_tile_description, tile.label, tile.value, tile.detail.orEmpty()).trimEnd(' ', ',')
    StatusPanel(modifier.testTag("dashboard-tile-${tile.id.name}").semantics(mergeDescendants = true) { contentDescription = description }) {
        Stat(tile.label, tile.value, detail = tile.detail, large = true, live = tile.live)
    }
}

@Composable
private fun CatalogLine(tiles: List<DashboardTile>, stacked: Boolean) {
    StatusPanel(Modifier.fillMaxWidth()) {
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) { tiles.forEach { CatalogStat(it, Modifier.fillMaxWidth()) } }
        else Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            // Two to a line: four figures in one line leave each label about 70dp, and "Profiles · 24h" wraps.
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    pair.forEach { CatalogStat(it, Modifier.weight(1f)) }
                    repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CatalogStat(tile: DashboardTile, modifier: Modifier) {
    val description = stringResource(R.string.dashboard_tile_description, tile.label, tile.value, tile.detail.orEmpty()).trimEnd(' ', ',')
    Stat(
        tile.label, tile.value, detail = null,
        modifier = modifier.testTag("dashboard-tile-${tile.id.name}").semantics(mergeDescendants = true) { contentDescription = description },
    )
}

// ---- Server health ----

private enum class HealthTone { Normal, Warn, Error }

private data class HealthCell(val label: String, val value: String, val detail: String? = null, val tone: HealthTone = HealthTone.Normal, val onClick: (() -> Unit)? = null)

@Composable
private fun HealthSection(data: DashboardSectionsData) {
    val insights = data.insights
    val status = insights.status.value
    SectionPanel(DashboardSection.Health, stringResource(R.string.dashboard_server_health_title)) {
        when {
            status != null -> HealthCells(healthCells(data, status))
            insights.status.error != null -> Text(insights.status.error.resolve(), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Warning)
            data.hostStats == null && data.hostErrorMessage == null ->
                Text(stringResource(R.string.status_health_loading), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        }
        if (status != null) insights.status.error?.let {
            Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
        }
        HostResources(data.hostStats, data.hostErrorMessage)
    }
}

@Composable
private fun healthCells(data: DashboardSectionsData, status: com.bloemstudios.admin.serverconfig.ServerStatus): List<HealthCell> {
    val insights = data.insights
    val now = Instant.now()
    val build = insights.build.value
    val nodes = insights.nodes.value
    val enabledNodes = nodes?.filter { it.enabled }.orEmpty()
    val healthyNodes = enabledNodes.count { it.healthy }
    val health = status.health
    val noValue = "—"
    return listOf(
        HealthCell(
            stringResource(R.string.dashboard_health_version),
            when {
                build == null -> noValue
                build.build_number > 0 -> stringResource(R.string.dashboard_health_version_build, build.build_number, build.display)
                else -> build.display.ifBlank { noValue }
            },
            if (build?.dirty == true) stringResource(R.string.dashboard_health_dirty) else null,
        ),
        HealthCell(
            stringResource(R.string.dashboard_health_uptime), formatUptime(status.started_at, now),
            if (status.restart_required) stringResource(R.string.dashboard_health_restart_required) else null,
            if (status.restart_required) HealthTone.Warn else HealthTone.Normal,
        ),
        dependencyCell(stringResource(R.string.dashboard_health_postgres), health?.postgres),
        dependencyCell(stringResource(R.string.dashboard_health_redis), health?.redis),
        HealthCell(
            stringResource(R.string.dashboard_health_nodes),
            when {
                nodes == null -> noValue
                enabledNodes.isEmpty() -> stringResource(R.string.dashboard_health_nodes_none)
                else -> stringResource(R.string.dashboard_health_nodes_ratio, healthyNodes, enabledNodes.size)
            },
            tone = if (nodes != null && healthyNodes < enabledNodes.size) HealthTone.Warn else HealthTone.Normal,
            onClick = data.onNodesRequested,
        ),
        HealthCell(
            stringResource(R.string.dashboard_health_errors),
            health?.errors_24h?.toString() ?: noValue,
            health?.let { plural(R.plurals.dashboard_health_warnings, it.warnings_24h, it.warnings_24h) },
            if (health != null && health.errors_24h > 0) HealthTone.Error else HealthTone.Normal,
        ),
    )
}

/** A dependency reports whether it is configured before whether it is up: a single-node server with no Redis is healthy. */
@Composable
private fun dependencyCell(label: String, component: HealthComponent?): HealthCell = when {
    component == null -> HealthCell(label, "—")
    !component.configured -> HealthCell(label, stringResource(R.string.dashboard_health_not_configured))
    component.ok != true -> HealthCell(label, stringResource(R.string.dashboard_health_unreachable), tone = HealthTone.Error)
    else -> HealthCell(label, stringResource(R.string.dashboard_health_ok), component.latency_ms?.let { formatLatency(it) })
}

/**
 * Labelled figures two to a row, with no tile behind them: a value never wraps under its label, both figures of a row
 * share one height, and a long one ("1482 · 2026.11.14", "Not configured") has the width of its column. One column
 * where the text is set large.
 */
@Composable
private fun HealthCells(cells: List<HealthCell>) {
    val columns = if (LocalDensity.current.fontScale >= HEALTH_WIDE_FONT_SCALE) 1 else 2
    Column {
        cells.chunked(columns).forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = VeilColors.Hairline)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
                row.forEach { HealthCellView(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HealthCellView(cell: HealthCell, modifier: Modifier) {
    val color = when (cell.tone) {
        HealthTone.Normal -> VeilColors.Text
        HealthTone.Warn -> VeilColors.Warning
        HealthTone.Error -> VeilColors.Danger
    }
    val description = stringResource(R.string.dashboard_health_cell_description, cell.label,
        listOfNotNull(cell.value, cell.detail).joinToString(", "))
    Column(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .then(if (cell.onClick != null) Modifier.clip(RoundedCornerShape(VeilShapes.InnerCorner)).clickable(role = Role.Button, onClick = cell.onClick) else Modifier)
            // Only the cell that opens a page needs a 48dp target; the rest are as tall as their text.
            .heightIn(min = if (cell.onClick != null) VeilSpacing.MinimumTouchTarget else 0.dp)
            .padding(vertical = VeilSpacing.Tiny),
    ) {
        Text(cell.label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(cell.value, style = MaterialTheme.typography.titleSmall.tabular(), color = color)
        cell.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted) }
    }
}

/**
 * The API host's resources as small labelled figures like the other health cells: CPU, memory, network in and out, the
 * fullest disk, and each GPU the host measures. The sample's age follows, and a stale sample says so.
 */
@Composable
private fun HostResources(hostStats: SystemResources?, hostErrorMessage: String?) {
    val context = LocalContext.current
    val system = hostStats?.system
    when {
        system != null && hostStats.available -> {
            val cells = hostCells(hostStats, system, context)
            if (cells.isNotEmpty()) Column {
                HorizontalDivider(color = VeilColors.Hairline)
                HealthCells(cells)
            }
            hostStats.sampled_at?.let { sampledAt ->
                Text(stringResource(if (hostStats.stale) R.string.dashboard_last_sample else R.string.dashboard_sampled_at, formatRelativeTime(sampledAt)),
                    style = MaterialTheme.typography.bodySmall, color = if (hostStats.stale) VeilColors.Warning else VeilColors.Muted)
            }
        }
        hostErrorMessage != null -> Text(hostErrorMessage, style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
    }
}

private fun String.noBreak() = replace(' ', '\u00A0')

@Composable
private fun hostCells(hostStats: SystemResources, system: SystemStats, context: android.content.Context): List<HealthCell> {
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes).replace(' ', '\u00A0')
    val locale = java.util.Locale.getDefault()
    return buildList {
        system.cpu_pct?.let { cpu ->
            val cores = system.cores
            val load = system.load1
            add(HealthCell(
                stringResource(R.string.dashboard_cpu_usage), stringResource(R.string.dashboard_cpu_percent, cpu),
                if (cores != null && load != null) plural(R.plurals.dashboard_host_cpu_detail, cores, cores, String.format(locale, "%.2f", load)) else null,
            ))
        }
        val usedMb = system.mem_used_mb
        val totalMb = system.mem_total_mb
        if (usedMb != null && totalMb != null) add(HealthCell(
            stringResource(R.string.dashboard_memory_usage), size(usedMb * BYTES_PER_MB),
            stringResource(R.string.dashboard_host_memory_detail, size(totalMb * BYTES_PER_MB)),
        ))
        system.netRxBytesPerSec?.let { add(HealthCell(stringResource(R.string.dashboard_host_network_in), perSecond(size(it.toLong())))) }
        system.netTxBytesPerSec?.let { add(HealthCell(stringResource(R.string.dashboard_host_network_out), perSecond(size(it.toLong())))) }
        fullestDisk(system.disks)?.let { disk ->
            val percent = diskPercent(disk)
            add(HealthCell(
                stringResource(R.string.dashboard_host_disk), stringResource(R.string.dashboard_percent_whole, percent),
                stringResource(R.string.dashboard_host_disk_detail, formatGigabytes(disk.used_gb, locale).noBreak(), formatGigabytes(disk.total_gb, locale).noBreak()),
                when (diskTone(percent)) { FigureTone.Normal -> HealthTone.Normal; FigureTone.Warn -> HealthTone.Warn; FigureTone.Error -> HealthTone.Error },
            ))
        }
        val gpus = measuredGpus(hostStats.gpu)
        gpus.forEachIndexed { index, gpu ->
            val busy = gpuBusyPercent(gpu)
            val vramUsed = gpu.vram_used_mb
            val vramTotal = gpu.vram_total_mb
            add(HealthCell(
                if (gpus.size > 1) stringResource(R.string.dashboard_host_gpu_numbered, index + 1) else stringResource(R.string.dashboard_host_gpu),
                busy?.let { stringResource(R.string.dashboard_percent_whole, it) } ?: "—",
                if (vramUsed != null && vramTotal != null && vramTotal > 0)
                    stringResource(R.string.dashboard_host_gpu_vram, size(vramUsed * BYTES_PER_MB), size(vramTotal * BYTES_PER_MB))
                else gpu.device.takeIf(String::isNotBlank),
            ))
        }
    }
}

// ---- Now playing ----

@Composable
private fun NowPlayingSection(data: DashboardSectionsData) {
    val sessions = data.sessions
    SectionPanel(
        DashboardSection.NowPlaying, stringResource(R.string.dashboard_now_playing_title),
        action = data.onStreamsRequested?.let { open -> SectionAction(stringResource(R.string.status_manage_streams), open) },
    ) {
        SessionSourceStatus(data.sourceState, data.sourceError, sessions != null)
        if (data.sessionsErrorMessage != null && data.sourceError == null) {
            Text(stringResource(R.string.status_sessions_stale), color = VeilColors.Warning, style = MaterialTheme.typography.bodySmall)
            Text(data.sessionsErrorMessage, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        } else if (sessions == null) {
            Text(stringResource(R.string.status_sessions_loading), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        } else if (sessions.isEmpty()) {
            Text(stringResource(R.string.dashboard_now_playing_idle), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        }
        sessions?.take(NOW_PLAYING_LIMIT)?.forEach { session ->
            HorizontalDivider(Modifier.padding(vertical = VeilSpacing.Small))
            SessionPreview(session, data.onStreamsRequested)
        }
        val more = (sessions?.size ?: 0) - NOW_PLAYING_LIMIT
        if (more > 0) data.onStreamsRequested?.let { open ->
            TextButton(onClick = open, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(plural(R.plurals.dashboard_now_playing_more, more, more))
            }
        }
    }
}

@Composable
private fun TopTitlesSection(state: SectionState<TopActivity>) {
    SectionPanel(DashboardSection.TopTitles, stringResource(R.string.dashboard_top_titles_title), caption = stringResource(R.string.dashboard_range_7d)) {
        val titles = state.value?.titles
        if (titles != null) {
            if (titles.isEmpty()) EmptyLine(stringResource(R.string.dashboard_top_empty))
            titles.take(LIST_LIMIT).forEach { title ->
                ListRow(title.title.ifBlank { title.media_item_id }, null,
                    plural(R.plurals.dashboard_top_row_plays, title.plays, title.plays, formatWatchTime(title.total_seconds)))
            }
        }
        SectionError(state)
    }
}

@Composable
private fun TopProfilesSection(state: SectionState<TopActivity>) {
    SectionPanel(DashboardSection.TopProfiles, stringResource(R.string.dashboard_top_profiles_title), caption = stringResource(R.string.dashboard_range_7d)) {
        val profiles = state.value?.profiles
        if (profiles != null) {
            if (profiles.isEmpty()) EmptyLine(stringResource(R.string.dashboard_top_empty))
            profiles.take(LIST_LIMIT).forEach { profile ->
                ListRow(topProfileLabel(profile), null,
                    plural(R.plurals.dashboard_top_row_plays, profile.plays, profile.plays, formatWatchTime(profile.total_seconds)))
            }
        } else EmptyLine(stringResource(R.string.dashboard_section_unavailable))
        // The titles section above reads the same request and already shows its failure.
    }
}

// ---- Libraries, users, recent activity ----

@Composable
private fun LibrariesSection(state: SectionState<List<DashboardLibrary>>) {
    SectionPanel(DashboardSection.Libraries, stringResource(R.string.dashboard_libraries_title)) {
        val libraries = state.value
        if (libraries != null) {
            if (libraries.isEmpty()) EmptyLine(stringResource(R.string.dashboard_libraries_empty))
            libraries.take(LIBRARY_LIMIT).forEach { library ->
                val paths = plural(R.plurals.dashboard_library_row, library.paths.size, library.paths.size, library.type)
                ListRow(library.name, paths, if (library.enabled) null else stringResource(R.string.dashboard_library_disabled), warn = !library.enabled)
            }
        }
        SectionError(state)
    }
}

@Composable
private fun UsersSection(state: SectionState<List<com.bloemstudios.admin.users.AdminUser>>, onUsersRequested: (() -> Unit)?) {
    SectionPanel(DashboardSection.Users, stringResource(R.string.dashboard_users_title),
        action = onUsersRequested?.let { SectionAction(stringResource(R.string.dashboard_section_manage_users), it) }) {
        val users = state.value
        if (users != null) {
            if (users.isEmpty()) EmptyLine(stringResource(R.string.dashboard_users_empty))
            usersByLastActive(users).take(LIST_LIMIT).forEach { user ->
                val role = if (user.enabled) user.role else stringResource(R.string.dashboard_user_row, user.role, stringResource(R.string.dashboard_user_disabled))
                ListRow(user.username, role, user.last_active_at?.let { formatRelativeTime(it) } ?: stringResource(R.string.dashboard_user_never_active),
                    warn = !user.enabled)
            }
        }
        SectionError(state)
    }
}

@Composable
private fun RecentActivitySection(state: SectionState<List<com.bloemstudios.admin.activity.PlaybackHistoryEntry>>) {
    SectionPanel(DashboardSection.RecentActivity, stringResource(R.string.dashboard_recent_activity_title)) {
        val entries = state.value
        if (entries != null) {
            if (entries.isEmpty()) EmptyLine(stringResource(R.string.dashboard_recent_empty))
            entries.take(LIST_LIMIT).forEach { entry ->
                ListRow(entry.media_title, entry.username, formatRelativeTime(entry.started_at))
            }
        }
        SectionError(state)
    }
}

// ---- Shared pieces ----

/** A quantity string; reads the configuration so a locale or font change re-resolves it. */
@Composable
internal fun plural(@androidx.annotation.PluralsRes id: Int, quantity: Int, vararg args: Any): String {
    LocalConfiguration.current
    return LocalContext.current.resources.getQuantityString(id, quantity, *args)
}

internal class SectionAction(val label: String, val onClick: () -> Unit)

@Composable
internal fun SectionPanel(
    section: DashboardSection, title: String, caption: String? = null, action: SectionAction? = null, small: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    StatusPanel(Modifier.testTag(section.tag)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted) }
            }
            action?.let { TextButton(onClick = it.onClick, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(it.label) } }
        }
        Spacer(Modifier.height(VeilSpacing.Tiny))
        content()
    }
}

@Composable
internal fun EmptyLine(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.padding(top = VeilSpacing.Small))

/** A refresh that failed; the last good value, when there is one, stays above it. */
@Composable
internal fun SectionError(state: SectionState<*>) {
    state.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning, modifier = Modifier.padding(top = VeilSpacing.Small)) }
}

@Composable
private fun ListRow(primary: String, secondary: String?, trailing: String?, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(primary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (warn) VeilColors.Warning else VeilColors.Muted,
            modifier = Modifier.widthIn(max = 140.dp)) }
    }
}

private const val NOW_PLAYING_LIMIT = 4
private const val LIST_LIMIT = 5
private const val LIBRARY_LIMIT = 8
private const val LARGE_FONT_SCALE = 1.5f
private const val HEALTH_WIDE_FONT_SCALE = 1.3f
private const val BYTES_PER_MB = 1024L * 1024L
