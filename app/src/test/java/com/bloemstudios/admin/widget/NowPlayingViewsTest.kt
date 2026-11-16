package com.bloemstudios.admin.widget

import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.StreamMethod
import com.bloemstudios.admin.streams.chartIndex
import com.bloemstudios.admin.streams.labelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Now playing widget draws from what was read: progress that moves between reads, data that stops moving when it is old,
 * streams that have ended, and what a lock screen may show.
 */
class NowPlayingViewsTest {
    private val minute = 60_000L
    private val readAt = 10_000_000_000L
    private val home = AdminServer("home", "https://home.test", "Home")
    private val lab = AdminServer("lab", "https://lab.test", "Lab")

    private fun stream(
        id: String = "s1", user: String = "maria", position: Double = 600.0, duration: Double? = 3_600.0, paused: Boolean = false,
        method: StreamMethod? = StreamMethod.Direct, transcode: Boolean = false,
    ) = StreamItem(
        sessionId = id, user = user, title = "Dune: Part Two", progress = null, transcode = transcode, paused = paused, bitrateKbps = 38_400,
        userLabel = "$user · Living room", method = method, source = "HEVC 4K · MKV", client = "Infuse 8.1 · tvOS", device = "tvOS",
        positionSeconds = position, durationSeconds = duration,
    )

    private fun slice(vararg streams: StreamItem, total: Int = streams.size, at: Long = readAt) =
        CachedSlice(Outcome.Ok, at, at, nowPlaying = NowPlayingData(total, streams = streams.toList(), bandwidthKbps = 38_400L * total, readAt = at))

    private fun view(now: Long, slices: Map<String, CachedSlice>, target: String? = "home", detail: Detail = Detail.Full, servers: List<AdminServer> = listOf(home, lab)) =
        WidgetViews.nowPlaying(WidgetInputs(target, servers, { id, kind -> slices[id].takeIf { kind == WidgetKind.NowPlaying } }, { null }, { null }, detail, now))

    private fun content(state: WidgetState<NowPlayingView>) = (state as WidgetState.Content).data

    // ---- Progress moves between reads, with no request ----

    @Test fun aPlayingStreamMovesForwardFromTheReadPosition() {
        val row = content(view(readAt + 5 * minute, mapOf("home" to slice(stream())))).rows.single()
        assertEquals(600.0 + 300.0, row.positionAt(readAt + 5 * minute), 0.001)
        assertEquals(900f / 3600f, row.progressAt(readAt + 5 * minute)!!, 0.0001f)
    }

    @Test fun aPausedStreamStaysWhereItWas() {
        val row = content(view(readAt + 5 * minute, mapOf("home" to slice(stream(paused = true))))).rows.single()
        assertEquals(600.0, row.positionAt(readAt + 5 * minute), 0.001)
    }

    @Test fun aStreamNeverMovesPastTheEndOfTheFile() {
        // The row itself: when the clock runs on past the end, the bar is full and the time is the length, not more.
        val row = StreamRowView("l", "maria", "T", null, false, false, positionSeconds = 3_500.0, durationSeconds = 3_600.0, readAt = readAt)
        assertEquals(3_600.0, row.positionAt(readAt + 10 * minute), 0.001)
        assertEquals(1f, row.progressAt(readAt + 10 * minute)!!, 0f)
    }

    @Test fun timeLeftCountsDownWithTheClock() {
        val row = content(view(readAt + 10 * minute, mapOf("home" to slice(stream(position = 600.0, duration = 3_600.0))))).rows.single()
        assertEquals(3_000.0 - 600.0, row.remainingAt(readAt + 10 * minute)!!, 0.001)
    }

    @Test fun aStreamOfUnknownLengthHasNoProgressOrTimeLeft() {
        val row = content(view(readAt + minute, mapOf("home" to slice(stream(duration = null))))).rows.single()
        assertNull(row.progressAt(readAt + minute))
        assertNull(row.remainingAt(readAt + minute))
    }

    @Test fun theTimeNeverGoesBackwardsIfTheClockIsBehind() {
        val row = content(view(readAt - minute, mapOf("home" to slice(stream())))).rows.single()
        assertEquals(600.0, row.positionAt(readAt - minute), 0.001)
    }

    // ---- Old data is not shown as current ----

    @Test fun dataOlderThanItsWindowStopsMovingAndSaysWhenItWasRead() {
        val now = readAt + WidgetViews.FRESH_WINDOW_MILLIS + minute
        val state = view(now, mapOf("home" to slice(stream()))) as WidgetState.Content
        assertEquals("Updated at the time it was read", readAt, state.staleAt)
        val row = state.data.rows.single()
        assertTrue(row.frozen)
        assertEquals("held at the last read, not carried forward as if playing", 600.0, row.positionAt(now), 0.001)
    }

    @Test fun freshDataHasNoUpdatedLabel() {
        val state = view(readAt + 5 * minute, mapOf("home" to slice(stream()))) as WidgetState.Content
        assertNull(state.staleAt)
        assertFalse(state.data.rows.single().frozen)
    }

    @Test fun aServerThatIsDownKeepsItsLastStreamsWithTheTimeItWasRead() {
        val down = slice(stream()).copy(outcome = Outcome.Unreachable)
        val state = view(readAt + 30 * minute, mapOf("home" to down))
        state as WidgetState.Unreachable
        assertEquals("Home", state.server)
        assertEquals(readAt, state.at)
        assertEquals(1, state.last?.rows?.size)
    }

    // ---- Streams that have ended ----

    @Test fun aStreamPastItsEndIsDroppedAndTheTotalsFollow() {
        val short = stream("a", "maria", position = 3_500.0, duration = 3_600.0, method = StreamMethod.Transcode, transcode = true)
        val long = stream("b", "jonas", position = 600.0, duration = 36_000.0)
        val data = NowPlayingData(2, transcoding = 1, bandwidthKbps = 40_000, streams = listOf(short, long), readAt = readAt)
        val slice = CachedSlice(Outcome.Ok, readAt, readAt, nowPlaying = data)
        // 100 seconds left at the read; 5 minutes later it is well past its end and still inside the 20-minute window.
        val now = readAt + 5 * minute
        val result = content(view(now, mapOf("home" to slice)))
        assertEquals(listOf("jonas"), result.rows.map { it.user })
        assertEquals(1, result.total)
        assertEquals(0, result.transcoding)
    }

    @Test fun whenEveryStreamHasEndedThereIsNothingLeftToCountAndTheWidgetSaysSo() {
        val short = stream("a", position = 3_500.0, duration = 3_600.0)
        val data = NowPlayingData(1, bandwidthKbps = 1, streams = listOf(short), readAt = readAt)
        val result = content(view(readAt + 5 * minute, mapOf("home" to CachedSlice(Outcome.Ok, readAt, readAt, nowPlaying = data))))
        assertEquals(0, result.total)
        assertTrue(result.rows.isEmpty())
    }

    // ---- What a card says, and where a tap goes ----

    @Test fun aRowCarriesWhatTheAppsCardShows() {
        val row = content(view(readAt, mapOf("home" to slice(stream())))).rows.single()
        assertEquals("maria · Living room", row.userLabel)
        assertEquals("HEVC 4K · MKV", row.source)
        assertEquals("Infuse 8.1 · tvOS", row.client)
        assertEquals(38_400, row.bitrateKbps)
        assertEquals(DeepLink.build("home", "now-playing", "s1"), row.link)
    }

    @Test fun anEntryFromBeforeTheMethodWasKeptFallsBackToTheTranscodeFlag() {
        val old = StreamItem("o", "ana", "Old", transcode = true)
        assertEquals(StreamMethod.Transcode, content(view(readAt, mapOf("home" to slice(old)))).rows.single().method)
        assertEquals(StreamMethod.Direct, content(view(readAt, mapOf("home" to slice(StreamItem("p", "ana", "Old"))))).rows.single().method)
    }

    @Test fun anAllServersWidgetNamesNoServerAndASingleOneNamesIt() {
        val slices = mapOf("home" to slice(stream("a")), "lab" to slice(stream("b")))
        assertNull(content(view(readAt, slices, target = null)).serverName)
        assertEquals("Home", content(view(readAt, slices, target = "home")).serverName)
        assertEquals(2, content(view(readAt, slices, target = null)).total)
    }

    // ---- Privacy: the lock screen shows counts, never a person or a title ----

    @Test fun withDetailsHiddenNoUserTitleDeviceOrClientReachesTheView() {
        val data = content(view(readAt, mapOf("home" to slice(stream("a", "maria"), stream("b", "jonas"))), detail = Detail.CountsOnly))
        assertTrue(data.detailsHidden)
        assertTrue("no row at all, so no name, title, device or client", data.rows.isEmpty())
        assertEquals(2, data.total)
        assertEquals(76_800L, data.bandwidthKbps)
        assertFalse(data.toString().contains("maria"))
        assertFalse(data.toString().contains("Dune"))
        assertFalse(data.toString().contains("Infuse"))
    }

    @Test fun theLockScreenOnlyHidesDetailsWhenTheSettingIsOff() {
        assertEquals(Detail.CountsOnly, WidgetPrivacy.detail(Surface.Keyguard, showDetailsOnLockScreen = false))
        assertEquals(Detail.Full, WidgetPrivacy.detail(Surface.Keyguard, showDetailsOnLockScreen = true))
    }

    @Test fun theHomeScreenAlwaysShowsFullDetailBecauseThePhonesUnlockIsTheGate() {
        assertEquals(Detail.Full, WidgetPrivacy.detail(Surface.HomeScreen, showDetailsOnLockScreen = false))
    }

    // ---- The same colour and word as the app ----

    @Test fun eachMethodHasTheAppsChartColourAndWord() {
        assertEquals(0, StreamMethod.Direct.chartIndex)
        assertEquals(1, StreamMethod.Remux.chartIndex)
        assertEquals(1, StreamMethod.DirectStream.chartIndex)
        assertEquals(2, StreamMethod.Transcode.chartIndex)
        assertNull(StreamMethod.Unknown.chartIndex)
        assertEquals(StreamMethod.entries.size, StreamMethod.entries.map { it.labelRes }.toSet().size)
    }

    @Test fun aTranscodeIsMarkedDifferentlyFromADirectPlay() {
        assertNotEquals(StreamMethod.Direct.chartIndex, StreamMethod.Transcode.chartIndex)
    }
}
