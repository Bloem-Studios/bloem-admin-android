package com.bloemstudios.admin.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the widgets are read again: the reasons, the gaps between runs, and the faster rhythm while something plays. */
class RefreshCadenceTest {
    private val minute = 60_000L

    // ---- Reasons and gaps ----

    @Test fun aPersonsOwnTapIsNeverHeldBack() {
        assertTrue(RefreshPolicy.allowed(RefreshReason.UserRefresh, nowMillis = 1_000, lastRunAtMillis = 999))
        assertEquals(0L, RefreshReason.UserRefresh.delayMillis)
    }

    @Test fun theAppGoingToTheBackgroundIsHonouredAtMostOnceAMinute() {
        val last = 10 * minute
        assertFalse(RefreshPolicy.allowed(RefreshReason.Background, last + 59_999, last))
        assertTrue(RefreshPolicy.allowed(RefreshReason.Background, last + minute, last))
        assertTrue("never run: nothing to be too soon after", RefreshPolicy.allowed(RefreshReason.Background, 5, null))
    }

    @Test fun aBurstOfWritesOrPushesIsMergedByAShortWaitNotDropped() {
        assertTrue(RefreshReason.AppSaved.delayMillis > 0)
        assertTrue(RefreshReason.Push.delayMillis > 0)
        assertTrue(RefreshPolicy.allowed(RefreshReason.AppSaved, 1, 0))
        assertTrue(RefreshPolicy.allowed(RefreshReason.Push, 1, 0))
    }

    // ---- The faster rhythm while something plays ----

    @Test fun nothingPlayingSchedulesNothing() {
        assertNull(ActiveCadence.nextDelayMillis(0, emptyList()))
    }

    @Test fun aStreamAboutToEndIsReadJustAfterItsEndButNeverSoonerThanFiveMinutes() {
        val now = 0L
        // Ends in 2 minutes: the end plus a moment is sooner than the floor, so the floor applies.
        assertEquals(ActiveCadence.FLOOR_MILLIS, ActiveCadence.nextDelayMillis(now, listOf(ActiveStream(endsAtMillis = 2 * minute))))
        // Ends in 7 minutes: read just after it.
        assertEquals(7 * minute + ActiveCadence.END_SLACK_MILLIS, ActiveCadence.nextDelayMillis(now, listOf(ActiveStream(7 * minute))))
        // Already past its end: still not before the floor.
        assertEquals(ActiveCadence.FLOOR_MILLIS, ActiveCadence.nextDelayMillis(now, listOf(ActiveStream(-5 * minute))))
    }

    @Test fun aLongFilmIsReadEveryTenMinutesAtMostNotEveryFive() {
        val delay = ActiveCadence.nextDelayMillis(0, listOf(ActiveStream(endsAtMillis = 90 * minute)))
        assertEquals(ActiveCadence.CEILING_MILLIS, delay)
        assertTrue("faster than the 15-minute idle rhythm", delay!! < 15 * minute)
    }

    @Test fun pausedStreamsAndStreamsOfUnknownLengthAreReadAtTheCeiling() {
        assertEquals(ActiveCadence.CEILING_MILLIS, ActiveCadence.nextDelayMillis(0, listOf(ActiveStream(null))))
    }

    @Test fun theSoonestEndWinsAmongSeveralStreams() {
        val delay = ActiveCadence.nextDelayMillis(0, listOf(ActiveStream(9 * minute), ActiveStream(6 * minute), ActiveStream(null)))
        assertEquals(6 * minute + ActiveCadence.END_SLACK_MILLIS, delay)
    }

    @Test fun noRateIsEverFasterThanTheFloor() {
        for (end in listOf(-100L, 0L, 1L, minute, 4 * minute, 5 * minute - 1)) {
            assertTrue(ActiveCadence.nextDelayMillis(0, listOf(ActiveStream(end)))!! >= ActiveCadence.FLOOR_MILLIS)
        }
    }

    // ---- Reading the streams out of the cache ----

    private fun slice(readAt: Long, total: Int, vararg streams: StreamItem, outcome: Outcome = Outcome.Ok) =
        CachedSlice(outcome, readAt, readAt, nowPlaying = NowPlayingData(total, streams = streams.toList(), readAt = readAt))

    @Test fun aPlayingStreamEndsWhenTheRestOfTheFileHasPlayed() {
        val stream = StreamItem("a", "ana", "T", positionSeconds = 600.0, durationSeconds = 1200.0)
        assertEquals(1_000_000L + 600_000L, stream.endsAt(1_000_000L))
    }

    @Test fun aPausedStreamOrOneWithNoLengthHasNoEnd() {
        assertNull(StreamItem("a", "ana", "T", paused = true, positionSeconds = 1.0, durationSeconds = 10.0).endsAt(0))
        assertNull(StreamItem("a", "ana", "T", positionSeconds = 1.0, durationSeconds = null).endsAt(0))
    }

    @Test fun everyLiveStreamCountsEvenTheOnesTheCacheDoesNotHold() {
        val streams = ActiveCadence.streamsIn(listOf(slice(0, total = 5, StreamItem("a", "ana", "T", positionSeconds = 0.0, durationSeconds = 600.0))))
        assertEquals(5, streams.size)
        assertEquals(600_000L, streams.first().endsAtMillis)
        assertNull(streams.last().endsAtMillis)
    }

    @Test fun aServerThatDidNotAnswerContributesNoStreams() {
        assertTrue(ActiveCadence.streamsIn(listOf(slice(0, 3, outcome = Outcome.Unreachable))).isEmpty())
    }
}
