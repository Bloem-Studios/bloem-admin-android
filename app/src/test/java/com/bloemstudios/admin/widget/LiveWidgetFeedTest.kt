package com.bloemstudios.admin.widget

import com.bloemstudios.admin.realtime.LiveChannels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** While the app is in front, a change its events socket reports is read straight away, at most once every 10 seconds. Widgets open no socket. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveWidgetFeedTest {
    private class Rig(scope: TestScope, shown: Set<WidgetKind>, var foreground: Boolean = true) {
        val reads = mutableListOf<Triple<Long, WidgetKind, Set<String>>>()
        val feed = LiveWidgetFeed(
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            foreground = { foreground },
            placedKinds = { shown },
            serverIdFor = { origin -> origin.removePrefix("https://").substringBefore('.') },
            refresh = { kind, ids -> reads += Triple(scope.testScheduler.currentTime, kind, ids) },
        )
    }

    private val sessions = LiveChannels.SESSIONS

    @Test fun theFirstChangeIsReadAtOnce() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        assertEquals(listOf(Triple(0L, WidgetKind.NowPlaying, setOf("home"))), rig.reads)
    }

    @Test fun aBurstInsideTenSecondsBecomesOneReadWhenTheGapEnds() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        for (t in listOf(1_000L, 3_000L, 9_000L)) { advanceTimeBy(t - currentTime); rig.feed.onCue("https://home.test", sessions); runCurrent() }
        assertEquals("nothing more yet: the gap is not over", 1, rig.reads.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals("one read, at the end of the gap, so the last change is not lost", 2, rig.reads.size)
        assertEquals(10_000L, rig.reads.last().first)
    }

    @Test fun neverMoreThanOneReadEveryTenSecondsHoweverOftenTheSocketTalks() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        // A change every second for two minutes.
        repeat(120) { rig.feed.onCue("https://home.test", sessions); advanceTimeBy(1_000); runCurrent() }
        val times = rig.reads.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= LiveWidgetFeed.MIN_GAP_MILLIS })
        assertTrue("about one in ten seconds", rig.reads.size in 11..13)
    }

    @Test fun aQuietSpellThenAChangeIsReadAtOnceAgain() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        advanceTimeBy(25_000)
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        assertEquals(listOf(0L, 25_000L), rig.reads.map { it.first })
    }

    @Test fun serversThatCuedInsideTheGapAreReadTogether() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        advanceTimeBy(2_000); rig.feed.onCue("https://lab.test", sessions); runCurrent()
        advanceTimeBy(2_000); rig.feed.onCue("https://home.test", sessions); runCurrent()
        advanceTimeBy(6_001); runCurrent()
        assertEquals(setOf("home", "lab"), rig.reads.last().third)
    }

    @Test fun aChannelNoPlacedWidgetShowsIsIgnored() = runTest {
        val rig = Rig(this, setOf(WidgetKind.Requests))
        rig.feed.onCue("https://home.test", sessions)
        rig.feed.onCue("https://home.test", LiveChannels.SETTINGS)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(rig.reads.isEmpty())
    }

    @Test fun eachChannelReachesTheWidgetThatShowsIt() {
        assertEquals(setOf(WidgetKind.NowPlaying), LiveWidgetFeed.kindsFor(LiveChannels.SESSIONS))
        assertEquals(setOf(WidgetKind.Libraries), LiveWidgetFeed.kindsFor(LiveChannels.SCANS))
        assertEquals(setOf(WidgetKind.Libraries), LiveWidgetFeed.kindsFor(LiveChannels.TASKS))
        assertEquals(setOf(WidgetKind.Libraries), LiveWidgetFeed.kindsFor(LiveChannels.JOBS))
        assertTrue(LiveWidgetFeed.kindsFor(LiveChannels.SETTINGS).isEmpty())
    }

    @Test fun eachKindHasItsOwnGap() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying, WidgetKind.Libraries))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        rig.feed.onCue("https://home.test", LiveChannels.SCANS); runCurrent()
        assertEquals("a Now playing read does not hold back a Libraries read", listOf(WidgetKind.NowPlaying, WidgetKind.Libraries), rig.reads.map { it.second })
    }

    @Test fun nothingIsReadWhenTheAppIsNotInFront() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying), foreground = false)
        rig.feed.onCue("https://home.test", sessions)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(rig.reads.isEmpty())
    }

    @Test fun aCueThatWaitsOutTheGapIsDroppedIfTheAppWentToTheBackground() = runTest {
        val rig = Rig(this, setOf(WidgetKind.NowPlaying))
        rig.feed.onCue("https://home.test", sessions); runCurrent()
        advanceTimeBy(2_000); rig.feed.onCue("https://home.test", sessions); runCurrent()
        rig.foreground = false
        advanceTimeBy(20_000); runCurrent()
        assertEquals("the background refresh covers it, and no socket exists then", 1, rig.reads.size)
    }

    @Test fun aServerThatIsNoLongerSavedIsNotRead() = runTest {
        val scope = this
        val reads = mutableListOf<Set<String>>()
        val feed = LiveWidgetFeed(
            scope.backgroundScope, { scope.testScheduler.currentTime }, { true }, { setOf(WidgetKind.NowPlaying) },
            serverIdFor = { null }, refresh = { _, ids -> reads += ids },
        )
        feed.onCue("https://gone.test", sessions); runCurrent()
        assertTrue(reads.isEmpty())
    }
}
