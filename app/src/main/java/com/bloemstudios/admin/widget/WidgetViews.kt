package com.bloemstudios.admin.widget

import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.requests.RequestDates
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.StreamMethod
import com.bloemstudios.admin.streams.progressFraction
import com.bloemstudios.admin.streams.projectedPosition
import com.bloemstudios.admin.streams.remainingSeconds
import java.time.Instant

/** What a widget has to say. The words are chosen where it is drawn; this says which situation it is. */
sealed interface WidgetState<out T> {
    /** All servers, with none saved. */
    data object NoServers : WidgetState<Nothing>

    /** The server this widget showed was removed from the app. */
    data object ServerRemoved : WidgetState<Nothing>

    /** Nothing read yet: a quiet placeholder. */
    data object Loading : WidgetState<Nothing>

    /** [server] is the server's name, or null for All servers. */
    data class SignedOut(val server: String?) : WidgetState<Nothing>

    /** The server (or every server) says it does not offer this feature. */
    data class Unsupported(val server: String?) : WidgetState<Nothing>

    /** [server] is the name, or null when [count] servers did not answer. [last] is the last good data, shown with [at]. */
    data class Unreachable<T>(val server: String?, val count: Int, val last: T?, val at: Long?) : WidgetState<T>

    /** A calm sentence: nothing playing, nothing waiting. [staleAt] is set when the answer is older than the refresh window. */
    data class Empty(val staleAt: Long?) : WidgetState<Nothing>

    /**
     * Data to draw. [staleAt] is the time of the oldest data when it is older than the refresh window (drawn as "Updated
     * 14:32"). [notAnswering] counts servers in All servers that gave nothing now (not reachable, or signed out).
     */
    data class Content<T>(val data: T, val staleAt: Long?, val notAnswering: Int = 0) : WidgetState<T>
}

// ---- What the widgets draw, already reduced to what [Detail] allows ----

/**
 * One live stream as the widget's card draws it, the same facts as the app's own session card. Where it is now is not stored
 * here: [positionAt] moves it forward from the last read, so the bar and the time advance between refreshes without a request.
 */
data class StreamRowView(
    val link: String,
    val user: String,
    val title: String,
    val progress: Float?,
    val transcode: Boolean,
    val paused: Boolean,
    /** "maria · Living room". */
    val userLabel: String = user,
    val method: StreamMethod = if (transcode) StreamMethod.Transcode else StreamMethod.Direct,
    /** "HEVC 4K · MKV". */
    val source: String? = null,
    /** "Infuse 8.1 · tvOS". */
    val client: String? = null,
    /** "tvOS". */
    val device: String? = null,
    /** `hw:QSV`, `sw`, `pending`, `unknown` or `audio` while transcoding. */
    val transcodeMode: String? = null,
    /** "H.264 1080p" while transcoding. */
    val target: String? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val bitrateKbps: Int? = null,
    /** When it was read (epoch milliseconds): the position moves on from here. */
    val readAt: Long = 0,
    /** Set when the data is older than its window: the position then stays where it was read instead of moving on. */
    val frozen: Boolean = false,
) {
    /** Where playback is at [nowMillis]: the read position plus the time since, while playing and while the data is fresh. */
    fun positionAt(nowMillis: Long): Double =
        projectedPosition(positionSeconds, durationSeconds, paused, if (frozen) 0L else (nowMillis - readAt).coerceAtLeast(0L))

    fun progressAt(nowMillis: Long): Float? = progressFraction(positionAt(nowMillis), durationSeconds)

    /** Seconds left at [nowMillis], or null when the file's length is not known. */
    fun remainingAt(nowMillis: Long): Double? = remainingSeconds(positionAt(nowMillis), durationSeconds)
}

data class NowPlayingView(
    val total: Int,
    val transcoding: Int,
    val bandwidthKbps: Long,
    /** Empty when details are hidden. */
    val rows: List<StreamRowView>,
    val detailsHidden: Boolean,
    /** The link a tap on the widget opens: Now playing on the one server, or on All servers. */
    val link: String,
    /** The server's name, or null for All servers. */
    val serverName: String? = null,
)

data class RequestRowView(
    val serverId: String,
    val requestId: String,
    val link: String,
    val title: String,
    val who: String?,
    val ageMillis: Long?,
)

data class RequestsView(
    val pending: Int,
    val rows: List<RequestRowView>,
    val detailsHidden: Boolean,
    val link: String,
    /** Set when a button failed since the last read: "Couldn't approve, open the app". */
    val failedAction: ActionNotice?,
    /** The server's name, or null for All servers. */
    val serverName: String? = null,
)

data class LibraryRowView(val link: String, val name: String, val lastScannedAt: Long?, val warning: Boolean, val scan: ScanBrief?)

data class LibrariesView(
    val total: Int,
    val withWarnings: Int,
    val scanning: Int,
    val rows: List<LibraryRowView>,
    val detailsHidden: Boolean,
    val link: String,
    /** The server's name, or null for All servers. */
    val serverName: String? = null,
)

data class NodeRowView(val link: String, val name: String, val state: String, val cpuPercent: Int?, val memoryPercent: Int?, val diskPercent: Int?)

data class NodesView(
    val total: Int,
    val needAttention: Int,
    val rows: List<NodeRowView>,
    val detailsHidden: Boolean,
    val link: String,
    /** The server's name, or null for All servers. */
    val serverName: String? = null,
)

/** A server's worth of inputs, with lookups so tests do not need a store. */
class WidgetInputs(
    val target: String?,
    val servers: List<AdminServer>,
    val slice: (serverId: String, kind: WidgetKind) -> CachedSlice?,
    /** The last health check's answer: true, false, or null when it has not run. */
    val reachable: (serverId: String) -> Boolean?,
    val notice: (serverId: String) -> ActionNotice?,
    val detail: Detail,
    val nowMillis: Long,
)

object WidgetViews {
    /** Data older than this shows "Updated 14:32": the 15-minute refresh plus the 5 minutes Android may add to it. */
    const val FRESH_WINDOW_MILLIS = 20L * 60_000L

    fun link(target: String?, kind: WidgetKind, item: String? = null): String =
        if (target == null) DeepLink.buildAll(kind.deepLinkSlug, item) else DeepLink.build(target, kind.deepLinkSlug, item)

    /** A stream that should have ended this long ago is gone from the server by now; the widget stops showing it until the next read says otherwise. */
    const val ENDED_GRACE_MILLIS = 60_000L

    fun nowPlaying(input: WidgetInputs): WidgetState<NowPlayingView> = resolve(
        input, WidgetKind.NowPlaying, { it.nowPlaying }, { it.total == 0 },
    ) { parts ->
        var dropped = 0
        var droppedTranscodes = 0
        var droppedKbps = 0L
        val rows = parts.flatMap { (server, data) ->
            // Older than its window the numbers are not current: they stay where they were read, and nothing is taken off.
            val frozen = input.nowMillis - data.readAt > FRESH_WINDOW_MILLIS
            data.streams.mapNotNull { stream ->
                val ended = !frozen && stream.endsAt(data.readAt)?.let { input.nowMillis > it + ENDED_GRACE_MILLIS } == true
                if (ended) {
                    dropped++
                    if (stream.transcode) droppedTranscodes++
                    droppedKbps += (stream.bitrateKbps ?: 0).coerceAtLeast(0)
                    return@mapNotNull null
                }
                StreamRowView(
                    link = link(server.id, WidgetKind.NowPlaying, stream.sessionId), user = stream.user, title = stream.title,
                    progress = stream.progress, transcode = stream.transcode, paused = stream.paused,
                    userLabel = stream.userLabel ?: stream.user,
                    method = stream.method ?: if (stream.transcode) StreamMethod.Transcode else StreamMethod.Direct,
                    source = stream.source, client = stream.client, device = stream.device, transcodeMode = stream.transcodeMode, target = stream.target,
                    positionSeconds = stream.positionSeconds, durationSeconds = stream.durationSeconds, bitrateKbps = stream.bitrateKbps,
                    readAt = data.readAt, frozen = frozen,
                )
            }
        }
        NowPlayingView(
            total = (parts.sumOf { it.second.total } - dropped).coerceAtLeast(0),
            transcoding = (parts.sumOf { it.second.transcoding } - droppedTranscodes).coerceAtLeast(0),
            bandwidthKbps = (parts.sumOf { it.second.bandwidthKbps } - droppedKbps).coerceAtLeast(0),
            rows = if (input.detail == Detail.Full) rows else emptyList(),
            detailsHidden = input.detail == Detail.CountsOnly,
            link = link(input.target, WidgetKind.NowPlaying),
            serverName = if (input.target != null) parts.singleOrNull()?.first?.displayName else null,
        )
    }

    fun requests(input: WidgetInputs): WidgetState<RequestsView> = resolve(
        input, WidgetKind.Requests, { it.requests }, { it.pending == 0 },
    ) { parts ->
        val rows = parts.flatMap { (server, data) ->
            data.items.map { item ->
                val created = RequestDates.parse(item.createdAt)?.toEpochMilli()
                RequestRowView(server.id, item.id, link(server.id, WidgetKind.Requests, item.id), item.title, item.who, created?.let { (input.nowMillis - it).coerceAtLeast(0) }) to created
            }
        }.sortedByDescending { it.second ?: Long.MIN_VALUE }.map { it.first }
        RequestsView(
            pending = parts.sumOf { it.second.pending },
            rows = if (input.detail == Detail.Full) rows.take(WIDGET_MAX_ITEMS) else emptyList(),
            detailsHidden = input.detail == Detail.CountsOnly,
            link = link(input.target, WidgetKind.Requests),
            serverName = nameOf(input, parts),
            failedAction = parts.firstNotNullOfOrNull { (server, _) -> input.notice(server.id) },
        )
    }

    fun libraries(input: WidgetInputs): WidgetState<LibrariesView> = resolve(
        input, WidgetKind.Libraries, { it.libraries }, { it.libraries.isEmpty() },
    ) { parts ->
        val all = parts.flatMap { (server, data) -> data.libraries.map { server to it } }
        LibrariesView(
            total = all.size,
            withWarnings = all.count { it.second.warning },
            scanning = all.count { it.second.scan != null },
            rows = if (input.detail == Detail.Full) {
                // Libraries that need a look come first: a scan running, then a warning, then the rest.
                all.sortedWith(compareBy({ it.second.scan == null }, { !it.second.warning }))
                    .take(WIDGET_MAX_ITEMS)
                    .map { (server, library) ->
                        LibraryRowView(link(server.id, WidgetKind.Libraries), library.name, library.lastScannedAt?.let(::instantOf), library.warning, library.scan)
                    }
            } else emptyList(),
            detailsHidden = input.detail == Detail.CountsOnly,
            link = link(input.target, WidgetKind.Libraries),
            serverName = nameOf(input, parts),
        )
    }

    fun nodes(input: WidgetInputs): WidgetState<NodesView> = resolve(
        input, WidgetKind.Nodes, { it.nodes }, { it.nodes.isEmpty() },
    ) { parts ->
        val all = parts.flatMap { (server, data) -> data.nodes.map { server to it } }
        NodesView(
            total = all.size,
            needAttention = all.count { it.second.state == "unhealthy" },
            rows = if (input.detail == Detail.Full) {
                all.sortedBy { it.second.state != "unhealthy" }.take(WIDGET_MAX_ITEMS).map { (server, node) ->
                    NodeRowView(link(server.id, WidgetKind.Nodes), node.name, node.state, node.cpuPercent, node.memoryPercent, node.diskPercent)
                }
            } else emptyList(),
            detailsHidden = input.detail == Detail.CountsOnly,
            link = link(input.target, WidgetKind.Nodes),
            serverName = nameOf(input, parts),
        )
    }

    private fun <D> nameOf(input: WidgetInputs, parts: List<Pair<AdminServer, D>>): String? =
        if (input.target != null) parts.singleOrNull()?.first?.displayName else null

    private fun instantOf(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    /** What one server said, before the servers are put together. */
    private sealed interface Part<out D> {
        data object Loading : Part<Nothing>
        data object SignedOut : Part<Nothing>
        data object Unsupported : Part<Nothing>
        data class Down<D>(val last: D?, val at: Long?) : Part<D>
        data class Data<D>(val data: D, val at: Long) : Part<D>
    }

    private fun <D> part(server: AdminServer, input: WidgetInputs, kind: WidgetKind, pick: (CachedSlice) -> D?): Part<D> {
        val slice = input.slice(server.id, kind)
        val down = input.reachable(server.id) == false
        val last = slice?.let(pick)
        return when {
            slice == null -> if (down) Part.Down(null, null) else Part.Loading
            slice.outcome == Outcome.SignInNeeded -> Part.SignedOut
            slice.outcome == Outcome.Unavailable -> Part.Unsupported
            slice.outcome == Outcome.Unreachable || down -> Part.Down(last, slice.dataAt)
            last == null -> Part.Loading
            else -> Part.Data(last, slice.dataAt ?: slice.attemptedAt)
        }
    }

    private fun <D, V> resolve(
        input: WidgetInputs,
        kind: WidgetKind,
        pick: (CachedSlice) -> D?,
        isEmpty: (D) -> Boolean,
        build: (List<Pair<AdminServer, D>>) -> V,
    ): WidgetState<V> {
        val servers = if (input.target != null) {
            listOf(input.servers.firstOrNull { it.id == input.target } ?: return WidgetState.ServerRemoved)
        } else {
            input.servers.ifEmpty { return WidgetState.NoServers }
        }
        val single = input.target != null
        val parts = servers.map { it to part(it, input, kind, pick) }
        fun staleOf(at: Long?): Long? = at?.takeIf { input.nowMillis - it > FRESH_WINDOW_MILLIS }

        val live = parts.mapNotNull { (server, p) -> (p as? Part.Data<D>)?.let { server to it } }
        val down = parts.mapNotNull { (server, p) -> (p as? Part.Down<D>)?.let { server to it } }
        if (live.isNotEmpty()) {
            val oldest = live.minOf { it.second.at }
            val notAnswering = down.size + parts.count { it.second is Part.SignedOut }
            if (live.all { isEmpty(it.second.data) } && notAnswering == 0) return WidgetState.Empty(staleOf(oldest))
            return WidgetState.Content(build(live.map { it.first to it.second.data }), staleOf(oldest), notAnswering)
        }
        if (down.isNotEmpty()) {
            // Servers that could not be reached keep their last data, shown with the time it was read.
            val withData = down.mapNotNull { (server, p) -> p.last?.let { server to it } }
            return WidgetState.Unreachable(
                server = if (single) servers.single().displayName else null,
                count = down.size,
                last = withData.takeIf { it.isNotEmpty() }?.let(build),
                at = down.mapNotNull { it.second.at }.minOrNull(),
            )
        }
        if (parts.all { it.second is Part.Unsupported }) return WidgetState.Unsupported(if (single) servers.single().displayName else null)
        if (parts.any { it.second is Part.SignedOut } && parts.none { it.second is Part.Loading }) {
            return WidgetState.SignedOut(if (single) servers.single().displayName else null)
        }
        return WidgetState.Loading
    }
}
