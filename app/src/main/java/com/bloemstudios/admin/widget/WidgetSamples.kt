package com.bloemstudios.admin.widget

import com.bloemstudios.admin.links.DeepLink

/**
 * Made-up content for the system's widget picker (Android 15 and later) and for the screenshot harness. Nothing here comes
 * from a server; the names are invented.
 */
object WidgetSamples {
    /** 14:40 on a fixed day, so the sample ages ("5 min") do not move. */
    const val NOW = 1_794_000_000_000L

    private const val SERVER = "sample-home"

    private fun link(kind: WidgetKind, item: String? = null) = DeepLink.build(SERVER, kind.deepLinkSlug, item)

    fun status(): WidgetState<ServerStatusView> {
        val rows = listOf(
            ServerRowView(DeepLink.build("s2", "now-playing"), "Office", Reach.NotReachable, WidgetCounts.Hidden, NOW - 20 * 60_000L),
            ServerRowView(DeepLink.build("s1", "now-playing"), "Home", Reach.Reachable, WidgetCounts.Known(3, 2), null),
            ServerRowView(DeepLink.build("s3", "now-playing"), "Lab", Reach.Reachable, WidgetCounts.Known(0, 1), null),
        )
        return WidgetState.Content(
            ServerStatusView(
                summary = WidgetModel.All(servers = 3, reachable = 2, notReachable = 1, streams = 3, requests = 3, countedServers = 2),
                rows = rows, servers = 3, down = 1, streams = 3, requests = 3, hasCounts = true, staleAt = null,
                streamsLink = DeepLink.buildAll("now-playing"), requestsLink = DeepLink.buildAll("requests"),
            ),
            staleAt = null,
        )
    }

    /**
     * The four streams the app's own Now playing screenshot shows (docs/screenshots/bloem-look/after/now-playing-*.png), so the
     * widget and the page can be set side by side. Made-up people and titles.
     */
    fun nowPlaying(detail: Detail = Detail.Full, count: Int = 4): WidgetState<NowPlayingView> {
        fun row(
            id: String, title: String, user: String, profile: String?, method: com.bloemstudios.admin.streams.StreamMethod, source: String,
            client: String, device: String, position: Double, duration: Double, kbps: Int, paused: Boolean = false,
            mode: String? = null, target: String? = null,
        ) = StreamRowView(
            link = link(WidgetKind.NowPlaying, id), user = user, title = title, progress = (position / duration).toFloat(),
            transcode = method == com.bloemstudios.admin.streams.StreamMethod.Transcode, paused = paused,
            userLabel = listOfNotNull(user, profile).joinToString(" · "), method = method, source = source, client = client, device = device,
            transcodeMode = mode, target = target, positionSeconds = position, durationSeconds = duration, bitrateKbps = kbps, readAt = NOW,
        )
        val m = com.bloemstudios.admin.streams.StreamMethod.entries.associateBy { it.name }
        val all = listOf(
            row("a", "Dune: Part Two", "maria", "Living room", m.getValue("Direct"), "HEVC 4K · MKV", "Infuse 8.1 · tvOS", "tvOS", 4330.0, 9960.0, 38_400),
            row("b", "Severance · S2E3 · Who Is Alive?", "jonas", null, m.getValue("Remux"), "H.264 1080p · MKV", "Bloem 1.4.2 · Android", "Android", 754.0, 2652.0, 12_800),
            row("c", "Planet Earth III · S1E2 · Oceans", "amira", null, m.getValue("Transcode"), "HEVC 4K · MKV", "Bloem 1.4.1 · iOS", "iOS", 1980.0, 3000.0, 8_200, mode = "hw:QSV", target = "H.264 1080p"),
            row("d", "The River", "tomas", null, m.getValue("Direct"), "H.264 1080p · MP4", "Web · Web", "Web", 1200.0, 5400.0, 6_100, paused = true),
        ).take(count)
        return WidgetState.Content(
            NowPlayingView(
                total = all.size, transcoding = all.count { it.transcode }, bandwidthKbps = all.sumOf { (it.bitrateKbps ?: 0).toLong() },
                rows = if (detail == Detail.Full) all else emptyList(), detailsHidden = detail == Detail.CountsOnly,
                link = DeepLink.build(SERVER, "now-playing"), serverName = "Bloem Home",
            ),
            staleAt = null,
        )
    }

    fun requests(detail: Detail = Detail.Full): WidgetState<RequestsView> {
        fun row(id: String, title: String, who: String, minutes: Long) =
            RequestRowView(SERVER, id, link(WidgetKind.Requests, id), title, who, minutes * 60_000L)
        val rows = listOf(
            row("r1", "Dune: Part Two", "maria", 12), row("r2", "Severance", "jonas", 60),
            row("r3", "Planet Earth III", "amira", 300), row("r4", "The Quiet Room", "tomas", 1_500),
        )
        return WidgetState.Content(
            RequestsView(
                pending = 4, rows = if (detail == Detail.Full) rows else emptyList(), detailsHidden = detail == Detail.CountsOnly,
                link = DeepLink.build(SERVER, "requests"), failedAction = null, serverName = "Bloem Home",
            ),
            staleAt = null,
        )
    }

    /** The three enabled libraries of the app's Libraries page (the debug fixtures): Music mid-scan and refreshing 340 of 1200, TV shows with a warning. */
    fun libraries(detail: Detail = Detail.Full): WidgetState<LibrariesView> {
        fun row(name: String, hoursAgo: Long?, warning: Boolean = false, scan: ScanBrief? = null) =
            LibraryRowView(link(WidgetKind.Libraries), name, hoursAgo?.let { NOW - it * 3_600_000L }, warning, scan)
        val rows = listOf(
            row("Music", 24, scan = ScanBrief("refreshing", percent = 28)), row("TV shows", 48, warning = true), row("Movies", 3),
        )
        return WidgetState.Content(
            LibrariesView(
                total = 3, withWarnings = 1, scanning = 1, rows = if (detail == Detail.Full) rows else emptyList(),
                detailsHidden = detail == Detail.CountsOnly, link = DeepLink.build(SERVER, "libraries"), serverName = "Bloem Home",
            ),
            staleAt = null,
        )
    }

    fun nodes(detail: Detail = Detail.Full): WidgetState<NodesView> {
        fun row(id: String, name: String, state: String, cpu: Int?, mem: Int?, disk: Int?) =
            NodeRowView(link(WidgetKind.Nodes), name, state, cpu, mem, disk)
        val rows = listOf(
            row("n3", "edge-1", "unhealthy", null, null, null), row("n1", "gpu-1", "healthy", 37, 37, 62),
            row("n2", "gpu-2", "draining", 12, 18, 41), row("n4", "edge-2", "healthy", 9, 22, 35),
        )
        return WidgetState.Content(
            NodesView(
                total = 4, needAttention = 1, rows = if (detail == Detail.Full) rows else emptyList(),
                detailsHidden = detail == Detail.CountsOnly, link = DeepLink.build(SERVER, "nodes"), serverName = "Bloem Home",
            ),
            staleAt = null,
        )
    }
}
