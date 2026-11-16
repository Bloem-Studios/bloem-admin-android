package com.bloemstudios.admin.widget

import com.bloemstudios.admin.network.ApiError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What reading one server comes to, for every outcome the widgets draw. */
class WidgetFetchTest {
    private class Fake(
        var nowPlaying: () -> NowPlayingData = { NowPlayingData(0) },
        var requests: () -> RequestsData = { RequestsData(0) },
        var libraries: () -> LibrariesData = { LibrariesData() },
        var nodes: () -> NodesData = { NodesData() },
    ) : WidgetSource {
        override suspend fun nowPlaying() = nowPlaying.invoke()
        override suspend fun requests() = requests.invoke()
        override suspend fun libraries() = libraries.invoke()
        override suspend fun nodes() = nodes.invoke()
    }

    private fun read(kind: WidgetKind, source: WidgetSource, previous: CachedSlice? = null, now: Long = 1_000L) =
        runBlocking { WidgetFetch.read(kind, source, previous, now) }

    @Test fun aGoodReadKeepsItsDataAndTime() {
        val slice = read(WidgetKind.NowPlaying, Fake(nowPlaying = { NowPlayingData(2, streams = listOf(StreamItem("s", "ana", "Harbour Lights"))) }))
        assertEquals(Outcome.Ok, slice.outcome)
        assertEquals(1_000L, slice.dataAt)
        assertEquals(2, slice.nowPlaying?.total)
    }

    @Test fun eachKindFillsItsOwnPayload() {
        assertEquals(3, read(WidgetKind.Requests, Fake(requests = { RequestsData(3) })).requests?.pending)
        assertTrue(read(WidgetKind.Libraries, Fake()).libraries != null)
        assertTrue(read(WidgetKind.Nodes, Fake()).nodes != null)
    }

    @Test fun aServerThatSaysItLacksTheFeatureIsUnavailableAndKeepsNothing() {
        val previous = read(WidgetKind.Requests, Fake(requests = { RequestsData(4) }))
        val slice = read(WidgetKind.Requests, Fake(requests = { throw FeatureUnavailable() }), previous)
        assertEquals(Outcome.Unavailable, slice.outcome)
        assertFalse(slice.hasData)
    }

    @Test fun aMissingRouteIsUnavailableToo() {
        for (code in listOf(403, 404, 405, 501)) {
            val slice = read(WidgetKind.Nodes, Fake(nodes = { throw ApiError(code, "nope") }))
            assertEquals("HTTP $code", Outcome.Unavailable, slice.outcome)
        }
    }

    @Test fun aRejectedSessionSaysToSignInAndDropsTheData() {
        val previous = read(WidgetKind.Requests, Fake(requests = { RequestsData(4) }))
        val slice = read(WidgetKind.Requests, Fake(requests = { throw ApiError(401, "expired") }), previous)
        assertEquals(Outcome.SignInNeeded, slice.outcome)
        assertFalse(slice.hasData)
    }

    @Test fun aServerThatDoesNotAnswerKeepsTheLastDataWithItsOriginalTime() {
        val previous = read(WidgetKind.NowPlaying, Fake(nowPlaying = { NowPlayingData(3) }), now = 1_000L)
        val slice = read(WidgetKind.NowPlaying, Fake(nowPlaying = { throw IOException("down") }), previous, now = 9_000L)
        assertEquals(Outcome.Unreachable, slice.outcome)
        assertEquals(3, slice.nowPlaying?.total)
        assertEquals(1_000L, slice.dataAt)
        assertEquals(9_000L, slice.attemptedAt)
    }

    @Test fun aServerError500CountsAsNotAnswering() {
        val slice = read(WidgetKind.NowPlaying, Fake(nowPlaying = { throw ApiError(500, "boom") }))
        assertEquals(Outcome.Unreachable, slice.outcome)
        assertNull(slice.dataAt)
    }

    @Test fun noPreviousDataMeansNothingToShowWhileUnreachable() {
        val slice = read(WidgetKind.Libraries, Fake(libraries = { throw IOException() }))
        assertEquals(Outcome.Unreachable, slice.outcome)
        assertFalse(slice.hasData)
    }

    @Test fun dataFromBeforeTheFeatureWentAwayIsNotRevived() {
        val unavailable = CachedSlice(Outcome.Unavailable, 500L)
        val slice = read(WidgetKind.Requests, Fake(requests = { throw IOException() }), unavailable)
        assertFalse(slice.hasData)
    }

    @Test fun noStoredSessionIsSignInNeededWithoutContactingTheServer() {
        assertEquals(Outcome.SignInNeeded, WidgetFetch.signInNeeded(5L).outcome)
    }

    @Test fun aTimedOutReadIsTreatedAsNotAnswering() {
        val previous = read(WidgetKind.Nodes, Fake(nodes = { NodesData(listOf(NodeItem("n", "edge", "healthy"))) }), now = 100L)
        val slice = WidgetFetch.timedOut(previous, 900L)
        assertEquals(Outcome.Unreachable, slice.outcome)
        assertEquals(100L, slice.dataAt)
    }
}
