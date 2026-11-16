package com.bloemstudios.admin.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own socket tells the widgets about a change; the widgets never open one. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCuesTest {
    private val heard = mutableListOf<Pair<String, String>>()

    @After fun tearDown() { LiveCues.listener = null }

    @Test fun aChangeEventOnTheSocketIsAnnouncedWithTheServerAndTheChannel() = runTest {
        LiveCues.listener = { origin, channel -> heard += origin to channel }
        val transport = FakeLiveTransport { testScheduler.currentTime }
        val api = testApi()
        val live = LiveConnection(api, transport, backgroundScope, FakeEnvironment(), LiveConfig(), { 1.0 })
        val screen = launch { live.retain(setOf(LiveChannels.SESSIONS, LiveChannels.SCANS)) }
        runCurrent()
        // The snapshot every connection starts with is a cue for the page that opened it, not a change for the widgets.
        assertTrue("snapshots are not announced", heard.isEmpty())
        transport.controls.single().onEvent(LiveChannels.SESSIONS)
        transport.controls.single().onEvent(LiveChannels.SCANS)
        assertEquals(listOf("https://example.com" to LiveChannels.SESSIONS, "https://example.com" to LiveChannels.SCANS), heard)
        screen.cancel()
    }

    @Test fun nothingIsAnnouncedAfterTheConnectionHasEnded() = runTest {
        LiveCues.listener = { origin, channel -> heard += origin to channel }
        val transport = FakeLiveTransport { testScheduler.currentTime }
        transport.script = { control, _ -> FakeLiveTransport.accept(control); throw SessionTransportException(SessionFailure.Connection) }
        val live = LiveConnection(testApi(), transport, backgroundScope, FakeEnvironment(), LiveConfig(), { 1.0 })
        val screen = launch { live.retain(setOf(LiveChannels.SESSIONS)) }
        runCurrent(); advanceTimeBy(10)
        val ended = transport.controls.first()
        ended.onEvent(LiveChannels.SESSIONS)
        assertTrue("a finished connection says nothing", heard.isEmpty())
        screen.cancel()
    }

    @Test fun aListenerThatThrowsDoesNotBreakTheSocket() = runTest {
        LiveCues.listener = { _, _ -> error("a widget refresh failed") }
        LiveCues.emit("https://example.com", LiveChannels.SESSIONS)
    }

    @Test fun withNoListenerNothingHappens() {
        LiveCues.listener = null
        LiveCues.emit("https://example.com", LiveChannels.SESSIONS)
    }
}
