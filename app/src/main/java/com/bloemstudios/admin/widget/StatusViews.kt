package com.bloemstudios.admin.widget

import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer

/** One saved server in the Server status widget. */
data class ServerRowView(
    val link: String,
    val name: String,
    val reach: Reach,
    val counts: WidgetCounts,
    /** When it stopped answering (epoch milliseconds), if the checks have seen that. */
    val downSince: Long?,
) {
    val streams: Int? get() = (counts as? WidgetCounts.Known)?.streams
}

/**
 * Everything the Server status widget draws. [summary] is the long-standing model (one server's counts, or All servers'
 * totals) that the small layout draws; [rows] is the same servers one by one for the medium and large layouts.
 */
data class ServerStatusView(
    val summary: WidgetModel,
    val rows: List<ServerRowView>,
    val servers: Int,
    val down: Int,
    val streams: Int,
    val requests: Int,
    /** Some server has a figure for streams or requests. */
    val hasCounts: Boolean,
    /** Time of the oldest check, when it is older than the refresh window. */
    val staleAt: Long?,
    val streamsLink: String,
    val requestsLink: String,
) {
    /** The tally dot: something needs you (a server is down) or something is live (a stream is playing). */
    val tally: Boolean get() = down > 0 || streams > 0
}

object StatusViews {
    fun map(target: String?, saved: List<AdminServer>, records: Map<String, ServerRecord>, nowMillis: Long): WidgetState<ServerStatusView> {
        val shown = if (target != null) {
            listOf(saved.firstOrNull { it.id == target } ?: return WidgetState.ServerRemoved)
        } else {
            saved.ifEmpty { return WidgetState.NoServers }
        }
        // Not one server has been checked yet: a quiet placeholder, not "not reachable".
        if (shown.all { records[it.id]?.status?.reachable == null }) return WidgetState.Loading

        val rows = shown.map { server ->
            val record = records[server.id]
            val model = WidgetModels.map(server.id, saved, records) as WidgetModel.Single
            ServerRowView(DeepLink.build(server.id, "now-playing"), server.displayName, model.reach, model.counts, record?.reach?.failingSince)
        }
        val checkedAt = shown.mapNotNull { records[it.id]?.status?.checkedAt }.minOrNull()
        val summary = WidgetModels.map(target, saved, records)
        val counted = rows.mapNotNull { (it.counts as? WidgetCounts.Known) }
        return WidgetState.Content(
            ServerStatusView(
                summary = summary,
                rows = rows.sortedWith(compareBy({ it.reach != Reach.NotReachable }, { it.name.lowercase() })),
                servers = rows.size,
                down = rows.count { it.reach == Reach.NotReachable },
                streams = counted.sumOf { it.streams },
                requests = counted.sumOf { it.requests },
                hasCounts = counted.isNotEmpty(),
                staleAt = checkedAt?.takeIf { nowMillis - it > WidgetViews.FRESH_WINDOW_MILLIS },
                streamsLink = WidgetViews.link(target, WidgetKind.NowPlaying),
                requestsLink = WidgetViews.link(target, WidgetKind.Requests),
            ),
            staleAt = null,
        )
    }
}
