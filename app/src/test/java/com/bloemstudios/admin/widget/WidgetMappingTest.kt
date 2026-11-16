package com.bloemstudios.admin.widget

import com.bloemstudios.admin.jobs.JobProgress
import com.bloemstudios.admin.libraries.AdminLibrary
import com.bloemstudios.admin.libraries.LibraryJob
import com.bloemstudios.admin.libraries.ScanRun
import com.bloemstudios.admin.nodes.NodeResources
import com.bloemstudios.admin.nodes.NodeSummary
import com.bloemstudios.admin.nodes.NodeSystem
import com.bloemstudios.admin.requests.MediaRequest
import com.bloemstudios.admin.streams.AdminSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The data mapping: what the API sends becomes what a widget keeps. */
class WidgetMappingTest {
    private fun session(
        id: String, user: String = "ana", title: String = "Harbour Lights", position: Double = 600.0, duration: Double? = 1200.0,
        method: String = "direct", effective: String? = null, paused: Boolean = false, kbps: Int? = 4000,
        series: String? = null, season: Int? = null, episode: Int? = null,
    ) = AdminSession(
        session_id = id, user_id = "u-$user", username = user, profile_id = "p", media_title = title, media_type = "movie",
        play_method = method, position_seconds = position, file_duration = duration, is_paused = paused,
        effective_play_method = effective, stream_bitrate_kbps = kbps, series_name = series, season_number = season, episode_number = episode,
    )

    @Test fun nowPlayingCountsEveryStreamAndKeepsThemAllSoTheLargeSizeCanScroll() {
        val sessions = (1..8).map { session("s$it", user = "user$it") }
        val data = WidgetMapping.nowPlaying(sessions)
        assertEquals(8, data.total)
        assertEquals(8, data.streams.size)
        assertEquals("s1", data.streams.first().sessionId)
        assertEquals(32_000L, data.bandwidthKbps)
    }

    @Test fun nowPlayingKeepsAtMostTheCapButCountsEveryStream() {
        val data = WidgetMapping.nowPlaying((1..40).map { session("s$it") })
        assertEquals(40, data.total)
        assertEquals(NOW_PLAYING_MAX_ITEMS, data.streams.size)
    }

    // The card carries what the app's own session card shows, named the way the app names it.

    private fun transcodeSession() = session("t", user = "amira", title = "Oceans", position = 1980.0, duration = 3000.0, kbps = 8200, series = "Planet Earth III", season = 1, episode = 2)
        .copy(
            profile_name = "Living room", device_platform = "ios", client_name = "Bloem", client_version = "1.4.1",
            video_decision = "transcode", audio_decision = "copy", effective_play_method = "transcode", transcode_hw_accel = "qsv",
            source_video_codec = "hevc", source_video_resolution = "2160p", source_container = "mkv",
            target_video_codec = "h264", target_resolution = "1080p",
        )

    @Test fun aStreamCarriesTheTitleUserSourceClientAndTranscodeDetailTheAppShows() {
        val item = WidgetMapping.nowPlaying(listOf(transcodeSession())).streams.single()
        assertEquals("Planet Earth III · S1E2 · Oceans", item.title)
        assertEquals("amira · Living room", item.userLabel)
        assertEquals(com.bloemstudios.admin.streams.StreamMethod.Transcode, item.method)
        assertEquals("HEVC 4K · MKV", item.source)
        assertEquals("Bloem 1.4.1 · iOS", item.client)
        assertEquals("iOS", item.device)
        assertEquals("hw:QSV", item.transcodeMode)
        assertEquals("H.264 1080p", item.target)
        assertEquals(1980.0, item.positionSeconds, 0.0)
        assertEquals(3000.0, item.durationSeconds!!, 0.0)
        assertEquals(8200, item.bitrateKbps)
    }

    @Test fun aDirectPlayCarriesNoTranscodeDetail() {
        val item = WidgetMapping.nowPlaying(listOf(session("d", effective = "direct"))).streams.single()
        assertEquals(com.bloemstudios.admin.streams.StreamMethod.Direct, item.method)
        assertNull(item.transcodeMode)
        assertNull(item.target)
    }

    @Test fun theProfileIsLeftOutWhenItRepeatsTheUser() {
        val item = WidgetMapping.nowPlaying(listOf(session("a", user = "maria").copy(profile_name = "Maria"))).streams.single()
        assertEquals("maria", item.userLabel)
    }

    @Test fun everyTranscodeModeHasItsOwnToken() {
        fun mode(accel: String?, video: String = "transcode", audio: String = "copy") =
            WidgetMapping.nowPlaying(listOf(session("m").copy(video_decision = video, audio_decision = audio, transcode_hw_accel = accel))).streams.single().transcodeMode
        assertEquals("sw", mode("none"))
        assertEquals("pending", mode("auto"))
        assertEquals("unknown", mode(null))
        assertEquals("hw:VAAPI", mode("vaapi"))
        assertEquals("audio", mode(null, video = "direct", audio = "transcode"))
    }

    @Test fun nowPlayingSaysDirectOrTranscodeAndHowFarAlong() {
        val data = WidgetMapping.nowPlaying(
            listOf(session("a", effective = "direct"), session("b", effective = "transcode", position = 300.0, paused = true)),
        )
        assertEquals(1, data.transcoding)
        assertFalse(data.streams[0].transcode)
        assertTrue(data.streams[1].transcode)
        assertEquals(0.5f, data.streams[0].progress)
        assertEquals(0.25f, data.streams[1].progress)
        assertTrue(data.streams[1].paused)
    }

    @Test fun anEpisodeShowsItsSeriesAndNumber() {
        val data = WidgetMapping.nowPlaying(listOf(session("a", title = "Pilot", series = "The Long Wire", season = 2, episode = 4)))
        assertEquals("The Long Wire · S2E4 · Pilot", data.streams.single().title)
    }

    @Test fun aStreamWithNoLengthHasNoProgress() {
        assertNull(WidgetMapping.nowPlaying(listOf(session("a", duration = null))).streams.single().progress)
    }

    @Test fun nothingPlayingIsAnEmptyList() {
        val data = WidgetMapping.nowPlaying(emptyList())
        assertEquals(0, data.total)
        assertTrue(data.streams.isEmpty())
        assertEquals(0L, data.bandwidthKbps)
    }

    private fun request(id: String, user: String?, title: String = "Glass Orchard", at: String? = "2026-11-15T10:00:00Z") = MediaRequest(
        id = id, provider = "tmdb", media_type = "movie", title = title, status = "pending", outcome = "active",
        requested_by_user_id = user, created_at = at,
    )

    @Test fun requestsKeepTheTotalAndTheNewestFiveWithNames() {
        val newest = (1..8).map { request("r$it", "u1") }
        val data = WidgetMapping.requests(waiting = 12, newest = newest, names = mapOf("u1" to "ana"))
        assertEquals(12, data.pending)
        assertEquals(WIDGET_MAX_ITEMS, data.items.size)
        assertEquals("ana", data.items.first().who)
        assertEquals("2026-11-15T10:00:00Z", data.items.first().createdAt)
    }

    @Test fun aRequesterWhoCannotBeLookedUpHasNoName() {
        val data = WidgetMapping.requests(1, listOf(request("r1", "u9"), request("r2", null)), mapOf("u1" to "ana"))
        assertNull(data.items[0].who)
        assertNull(data.items[1].who)
    }

    private val now = Instant.parse("2026-11-15T12:00:00Z")

    private fun library(id: String, name: String = id, scanned: String? = "2026-11-15T09:00:00Z", warning: String? = null, enabled: Boolean = true) =
        AdminLibrary(id = id, name = name, enabled = enabled, last_scanned_at = scanned, scan_warning_code = warning)

    private fun run(library: String, status: String, id: String = "run-$library-$status") = ScanRun(
        id = id, library_id = library, status = status, requested_at = "2026-11-15T11:59:00Z", started_at = "2026-11-15T11:59:10Z",
    )

    @Test fun aRunningScanShowsAsRunningAndAQueuedOneAsQueued() {
        val data = WidgetMapping.libraries(
            listOf(library("a"), library("b"), library("c")),
            listOf(run("a", "running"), run("b", "accepted"), run("c", "completed")),
            emptyList(), scansAvailable = true, nowMillis = now.toEpochMilli(),
        )
        assertEquals("running", data.libraries[0].scan?.state)
        assertEquals("queued", data.libraries[1].scan?.state)
        assertNull(data.libraries[2].scan)
    }

    @Test fun aMetadataRefreshReportsItsPercentage() {
        val job = LibraryJob(id = "j", state = "running", library_id = "a", progress = JobProgress(current = 30, total = 120, unit = "items"))
        val data = WidgetMapping.libraries(listOf(library("a")), emptyList(), listOf(job), scansAvailable = true, nowMillis = now.toEpochMilli())
        assertEquals(ScanBrief("refreshing", 25), data.libraries.single().scan)
    }

    @Test fun aFinishedRefreshIsNotShown() {
        val job = LibraryJob(id = "j", state = "succeeded", terminal = true, library_id = "a")
        val data = WidgetMapping.libraries(listOf(library("a")), emptyList(), listOf(job), scansAvailable = true, nowMillis = now.toEpochMilli())
        assertNull(data.libraries.single().scan)
    }

    @Test fun aWarningAndTheLastScanAreKept() {
        val data = WidgetMapping.libraries(
            listOf(library("a", warning = "empty_root", scanned = "2026-11-14T08:00:00Z"), library("b", scanned = null)),
            emptyList(), emptyList(), scansAvailable = true, nowMillis = now.toEpochMilli(),
        )
        assertTrue(data.libraries[0].warning)
        assertEquals("2026-11-14T08:00:00Z", data.libraries[0].lastScannedAt)
        assertFalse(data.libraries[1].warning)
        assertNull(data.libraries[1].lastScannedAt)
    }

    @Test fun aDisabledLibraryIsLeftOut() {
        val data = WidgetMapping.libraries(listOf(library("a"), library("b", enabled = false)), emptyList(), emptyList(), true, now.toEpochMilli())
        assertEquals(listOf("a"), data.libraries.map { it.id })
    }

    @Test fun whenScansAreUnavailableTheLibrariesStillShowWithoutScanState() {
        val data = WidgetMapping.libraries(listOf(library("a")), listOf(run("a", "running")), emptyList(), scansAvailable = false, nowMillis = now.toEpochMilli())
        // The caller passes no runs when scans are unavailable; the flag tells the widget why there is no scan state.
        assertFalse(data.scansAvailable)
    }

    private fun node(
        id: String, healthy: Boolean = true, enabled: Boolean = true, drain: Boolean = false, cpu: Int = 40, memUsed: Long = 4_000, memTotal: Long = 8_000,
        disk: Pair<Double, Double> = 70.0 to 100.0, sampled: String = "2026-11-15T11:59:50Z", checked: String = "2026-11-15T11:59:55Z",
    ) = NodeSummary(
        id = id, name = "node-$id", type = "transcode", enabled = enabled, healthy = healthy, drain_requested = drain,
        last_health_check = checked,
        resources = NodeResources(
            sampled_at = sampled,
            system = NodeSystem(cpu_pct = cpu, mem_used_mb = memUsed, mem_total_mb = memTotal, disks = listOf(com.bloemstudios.admin.nodes.NodeDisk(used_gb = disk.first, total_gb = disk.second))),
        ),
    )

    @Test fun aHealthyNodeShowsCpuMemoryAndDisk() {
        val item = WidgetMapping.nodes(listOf(node("a")), now).nodes.single()
        assertEquals("healthy", item.state)
        assertEquals(40, item.cpuPercent)
        assertEquals(50, item.memoryPercent)
        assertEquals(70, item.diskPercent)
    }

    @Test fun nodeStatesAreNamedByTheAppsOwnRule() {
        val states = WidgetMapping.nodes(
            listOf(node("a", healthy = false), node("b", enabled = false), node("c", drain = true), node("d")), now,
        ).nodes.map { it.state }
        assertEquals(listOf("unhealthy", "disabled", "draining", "healthy"), states)
    }

    @Test fun anUnhealthyNodeShowsNoNumbersFromBeforeItFailed() {
        val item = WidgetMapping.nodes(listOf(node("a", healthy = false)), now).nodes.single()
        assertNull(item.cpuPercent)
        assertNull(item.memoryPercent)
        assertNull(item.diskPercent)
    }

    @Test fun aSampleThatStoppedMovingShowsNoNumbers() {
        val item = WidgetMapping.nodes(listOf(node("a", checked = "2026-11-15T11:00:00Z", sampled = "2026-11-15T11:00:00Z")), now).nodes.single()
        assertNull(item.cpuPercent)
        assertEquals("healthy", item.state)
    }
}
