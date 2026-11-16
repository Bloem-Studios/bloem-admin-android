package com.bloemstudios.admin.widget

import com.bloemstudios.admin.libraries.AdminLibrary
import com.bloemstudios.admin.libraries.LibraryJob
import com.bloemstudios.admin.libraries.ScanRun
import com.bloemstudios.admin.libraries.ScanState
import com.bloemstudios.admin.libraries.libraryScanStatus
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.getNodeCapabilities
import com.bloemstudios.admin.network.api.getRequestCapabilities
import com.bloemstudios.admin.network.api.getRequestCounts
import com.bloemstudios.admin.network.api.getScanCapability
import com.bloemstudios.admin.network.api.listAdminLibraries
import com.bloemstudios.admin.network.api.listLibraryRefreshJobs
import com.bloemstudios.admin.network.api.listNodeSummaries
import com.bloemstudios.admin.network.api.listRequestQueuePage
import com.bloemstudios.admin.network.api.listScanRuns
import com.bloemstudios.admin.network.api.listSessions
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.nodes.NodeState
import com.bloemstudios.admin.nodes.NodeSummary
import com.bloemstudios.admin.nodes.nodeLoad
import com.bloemstudios.admin.nodes.nodeState
import com.bloemstudios.admin.requests.MediaRequest
import com.bloemstudios.admin.requests.RequestQueueView
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.StreamMethod
import com.bloemstudios.admin.streams.TranscodeMode
import com.bloemstudios.admin.streams.progressFraction
import com.bloemstudios.admin.streams.sourceContainerLabel
import com.bloemstudios.admin.streams.streamClientLine
import com.bloemstudios.admin.streams.streamDeviceLabel
import com.bloemstudios.admin.streams.streamMethodOf
import com.bloemstudios.admin.streams.streamTitle
import com.bloemstudios.admin.streams.streamUserLabel
import com.bloemstudios.admin.streams.transcodeModeOf
import com.bloemstudios.admin.streams.videoFlow
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** A server answered, and says it does not offer this feature. */
class FeatureUnavailable : Exception()

/** The reads the widgets make from one signed-in server. A seam, so the mapping and the states are testable without a network. */
interface WidgetSource {
    suspend fun nowPlaying(): NowPlayingData
    suspend fun requests(): RequestsData
    suspend fun libraries(): LibrariesData
    suspend fun nodes(): NodesData
}

/** Turns what the API sends into what a widget keeps. Pure, so every rule here has a unit test. */
object WidgetMapping {
    fun nowPlaying(sessions: List<AdminSession>): NowPlayingData = NowPlayingData(
        total = sessions.size,
        transcoding = sessions.count { streamMethodOf(it) == StreamMethod.Transcode },
        bandwidthKbps = sessions.sumOf { (it.stream_bitrate_kbps ?: 0).coerceAtLeast(0).toLong() },
        streams = sessions.take(NOW_PLAYING_MAX_ITEMS).map { stream(it) },
    )

    /** One session as the app's own card names it (streams/StreamPresentation.kt): the same title, user, path and transcode detail. */
    fun stream(session: AdminSession): StreamItem {
        val method = streamMethodOf(session)
        val video = videoFlow(session)
        return StreamItem(
            sessionId = session.session_id,
            user = session.username,
            title = streamTitle(session),
            progress = progressFraction(session.position_seconds, session.file_duration),
            transcode = method == StreamMethod.Transcode,
            paused = session.is_paused,
            bitrateKbps = session.stream_bitrate_kbps,
            userLabel = streamUserLabel(session),
            method = method,
            source = listOfNotNull(video.source, sourceContainerLabel(session)).joinToString(" · ").ifEmpty { null },
            client = streamClientLine(session),
            device = streamDeviceLabel(session),
            transcodeMode = transcodeModeOf(session)?.let(::encodeTranscodeMode),
            target = video.target,
            positionSeconds = session.position_seconds,
            durationSeconds = session.file_duration,
        )
    }

    private fun encodeTranscodeMode(mode: TranscodeMode): String = when (mode) {
        is TranscodeMode.Hardware -> "hw:${mode.accel}"
        TranscodeMode.Software -> "sw"
        TranscodeMode.Pending -> "pending"
        TranscodeMode.Unknown -> "unknown"
        TranscodeMode.AudioOnly -> "audio"
    }

    fun requests(waiting: Int, newest: List<MediaRequest>, names: Map<String, String>): RequestsData = RequestsData(
        pending = waiting.coerceAtLeast(0),
        items = newest.take(WIDGET_MAX_ITEMS).map {
            RequestItem(it.id, it.title, who = it.requested_by_user_id?.let(names::get), createdAt = it.created_at)
        },
    )

    fun libraries(
        libraries: List<AdminLibrary>,
        runs: List<ScanRun>,
        refreshJobs: List<LibraryJob>,
        scansAvailable: Boolean,
        nowMillis: Long,
    ): LibrariesData = LibrariesData(
        scansAvailable = scansAvailable,
        libraries = libraries.filter { it.enabled }.map { library ->
            val scan = libraryScanStatus(library.id, runs, pending = null, nowMillis = nowMillis)
            val job = refreshJobs.firstOrNull { it.library_id == library.id && !it.terminal }
            LibraryItem(
                id = library.id,
                name = library.name,
                lastScannedAt = library.last_scanned_at,
                warning = library.scan_warning_code != null,
                scan = when {
                    scan?.state == ScanState.Running -> ScanBrief("running")
                    scan?.state == ScanState.Queued -> ScanBrief("queued")
                    job != null -> ScanBrief("refreshing", job.progress?.takeIf { it.total > 0 }?.let { (it.current * 100 / it.total).toInt().coerceIn(0, 100) })
                    else -> null
                },
            )
        },
    )

    fun nodes(summaries: List<NodeSummary>, now: Instant): NodesData = NodesData(
        summaries.map { node ->
            val load = nodeLoad(node, now)
            NodeItem(
                id = node.id,
                name = node.name,
                state = when (nodeState(node)) {
                    NodeState.Healthy -> "healthy"
                    NodeState.Draining -> "draining"
                    NodeState.Drained -> "drained"
                    NodeState.Unhealthy -> "unhealthy"
                    NodeState.Disabled -> "disabled"
                },
                cpuPercent = load?.cpuPercent,
                memoryPercent = load?.memoryPercent,
                diskPercent = load?.diskPercent,
            )
        },
    )
}

/** The real source: the same signed-in client and the same API calls the pages make. */
class ClientWidgetSource(private val client: ApiClient, private val clock: () -> Long = System::currentTimeMillis) : WidgetSource {
    override suspend fun nowPlaying(): NowPlayingData = WidgetMapping.nowPlaying(client.listSessions())

    override suspend fun requests(): RequestsData {
        // A server without request administration says so here; Requests is not a page it has.
        if (!client.getRequestCapabilities().available) throw FeatureUnavailable()
        val waiting = client.getRequestCounts().needs_approval
        if (waiting == 0) return RequestsData(0)
        val page = client.listRequestQueuePage(RequestQueueView.NeedsApproval, limit = WIDGET_MAX_ITEMS)
        // Names are a convenience: without them the row shows the title and its age.
        val names = try {
            client.listUsers().associate { it.id to it.username }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyMap()
        }
        return WidgetMapping.requests(waiting, page.items, names)
    }

    override suspend fun libraries(): LibrariesData {
        val libraries = client.listAdminLibraries()
        val scansAvailable = try {
            client.getScanCapability().state == "available"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        val runs = if (scansAvailable) optional { client.listScanRuns(null) } else null
        val jobs = optional { client.listLibraryRefreshJobs() }.orEmpty()
        return WidgetMapping.libraries(libraries, runs.orEmpty(), jobs, scansAvailable && runs != null, clock())
    }

    override suspend fun nodes(): NodesData {
        // The same test the app uses to decide whether the Nodes page exists (AutomationPages).
        if (!client.getNodeCapabilities().let { it.summary && it.allowed && it.state == "available" }) throw FeatureUnavailable()
        return WidgetMapping.nodes(client.listNodeSummaries(), Instant.ofEpochMilli(clock()))
    }

    private suspend fun <T> optional(read: suspend () -> T): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/** Reads one widget kind from one server and says what that came to. */
object WidgetFetch {
    /**
     * [previous] is the slice kept from the last read. When the server does not answer, its data stays (with the time it was
     * read); when the feature or the sign-in is gone, it does not.
     */
    suspend fun read(kind: WidgetKind, source: WidgetSource, previous: CachedSlice?, nowMillis: Long): CachedSlice = try {
        when (kind) {
            WidgetKind.NowPlaying -> CachedSlice(Outcome.Ok, nowMillis, nowMillis, nowPlaying = source.nowPlaying().copy(readAt = nowMillis))
            WidgetKind.Requests -> CachedSlice(Outcome.Ok, nowMillis, nowMillis, requests = source.requests())
            WidgetKind.Libraries -> CachedSlice(Outcome.Ok, nowMillis, nowMillis, libraries = source.libraries())
            WidgetKind.Nodes -> CachedSlice(Outcome.Ok, nowMillis, nowMillis, nodes = source.nodes())
            WidgetKind.ServerStatus -> throw IllegalArgumentException("Server status is read by the server check")
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: FeatureUnavailable) {
        CachedSlice(Outcome.Unavailable, nowMillis)
    } catch (error: ApiError) {
        when (error.statusCode) {
            401 -> CachedSlice(Outcome.SignInNeeded, nowMillis)
            // The route is not there, or this account may not use it: either way the server does not offer it to this app.
            403, 404, 405, 501 -> CachedSlice(Outcome.Unavailable, nowMillis)
            else -> unreachable(previous, nowMillis)
        }
    } catch (_: Exception) {
        unreachable(previous, nowMillis)
    }

    /** No stored session: the server is not contacted. */
    fun signInNeeded(nowMillis: Long) = CachedSlice(Outcome.SignInNeeded, nowMillis)

    /** The attempt did not finish in time. */
    fun timedOut(previous: CachedSlice?, nowMillis: Long) = unreachable(previous, nowMillis)

    private fun unreachable(previous: CachedSlice?, nowMillis: Long): CachedSlice =
        if (previous != null && previous.outcome != Outcome.Unavailable && previous.outcome != Outcome.SignInNeeded && previous.hasData) {
            previous.copy(outcome = Outcome.Unreachable, attemptedAt = nowMillis)
        } else {
            CachedSlice(Outcome.Unreachable, nowMillis)
        }
}
