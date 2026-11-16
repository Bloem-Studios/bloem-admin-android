package com.bloemstudios.admin.organization

import com.bloemstudios.admin.realtime.FakeEnvironment
import com.bloemstudios.admin.realtime.FakeLiveTransport
import com.bloemstudios.admin.realtime.LiveConfig
import com.bloemstudios.admin.realtime.LiveConnection
import com.bloemstudios.admin.realtime.LiveStatus
import com.bloemstudios.admin.realtime.OrganizationLiveChannels
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.realtime.testApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * What an organization page does with the live socket: it subscribes while it is visible, reads again when its channel
 * changes, keeps what it did before when the server has no socket, and does nothing in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationLiveWatchTest {
    private val streams = setOf(OrganizationLiveChannels.STREAMS)

    private class Rig(val live: LiveConnection, val transport: FakeLiveTransport, val device: FakeEnvironment)

    private fun TestScope.rig(script: (suspend (com.bloemstudios.admin.realtime.LiveControl, Int) -> Unit)? = null): Rig {
        val transport = FakeLiveTransport { testScheduler.currentTime }
        script?.let { transport.script = it }
        val device = FakeEnvironment()
        return Rig(LiveConnection(testApi(), transport, backgroundScope, device, LiveConfig(), { 1.0 }), transport, device)
    }

    @Test fun `a visible page subscribes to its channel and reads when it opens`() = runTest {
        val rig = rig()
        var reads = 0
        val page = launch { watchOrganization(true, { rig.live }, streams) { reads++ } }
        runCurrent()
        assertEquals(1, rig.transport.attempts); assertEquals(1, rig.live.holdersOf(OrganizationLiveChannels.STREAMS))
        assertEquals(LiveStatus.Live, rig.live.status.value)
        assertEquals("the open", 1, reads)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("then the connection's own snapshot, once", 2, reads)
        page.cancel()
    }

    @Test fun `a change on the page's channel reads the list again, a change on another page's channel does not`() = runTest {
        val rig = rig()
        var reads = 0
        val page = launch { watchOrganization(true, { rig.live }, streams) { reads++ } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        val settled = reads
        rig.transport.controls.last().onEvent(OrganizationLiveChannels.STREAMS); advanceTimeBy(200); runCurrent()
        assertEquals(settled + 1, reads)
        advanceTimeBy(5_000)
        rig.transport.controls.last().onEvent(OrganizationLiveChannels.PEOPLE); advanceTimeBy(200); runCurrent()
        assertEquals(settled + 1, reads)
        page.cancel()
    }

    @Test fun `a burst of changes is one read at a time`() = runTest {
        val rig = rig()
        val times = mutableListOf<Long>()
        val page = launch { watchOrganization(true, { rig.live }, streams) { times += testScheduler.currentTime } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        val before = times.size
        repeat(100) { rig.transport.controls.last().onEvent(OrganizationLiveChannels.STREAMS); advanceTimeBy(50) }
        advanceTimeBy(5_000); runCurrent()
        assertTrue("100 changes in five seconds are a few reads, not 100: ${times.size - before}", times.size - before in 1..4)
        assertTrue("two seconds apart at least", times.zipWithNext { a, b -> b - a }.drop(before - 1).all { it >= 2_000 })
        page.cancel()
    }

    @Test fun `leaving the page lets go of the socket`() = runTest {
        val rig = rig()
        val page = launch { watchOrganization(true, { rig.live }, streams) { } }
        runCurrent(); assertEquals(1, rig.transport.active)
        page.cancel(); runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertEquals(0, rig.transport.active); assertEquals(0, rig.live.holdersOf(OrganizationLiveChannels.STREAMS))
    }

    @Test fun `two pages share one socket`() = runTest {
        val rig = rig()
        val first = launch { watchOrganization(true, { rig.live }, streams) { } }
        val second = launch { watchOrganization(true, { rig.live }, setOf(OrganizationLiveChannels.PEOPLE)) { } }
        runCurrent()
        assertEquals(1, rig.transport.attempts); assertEquals(1, rig.transport.active)
        first.cancel(); second.cancel()
    }

    // ---- Keeping what the page did before ----

    @Test fun `a server without the feature opens no socket and the page reads on open only`() = runTest {
        val rig = rig()
        var reads = 0
        var asked = false
        val page = launch { watchOrganization(false, { asked = true; rig.live }, streams) { reads++ } }
        runCurrent(); advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(1, reads); assertFalse("the socket is not even created", asked); assertEquals(0, rig.transport.attempts)
        page.cancel()
    }

    @Test fun `a channel the server does not serve leaves the page reading on open only`() = runTest {
        val rig = rig { control, _ ->
            com.bloemstudios.admin.realtime.FakeLiveTransport.accept(control, rejected = mapOf(OrganizationLiveChannels.STREAMS to "not_offered"))
            com.bloemstudios.admin.realtime.FakeLiveTransport.quiet(control)
        }
        var reads = 0
        val page = launch { watchOrganization(true, { rig.live }, streams) { reads++ } }
        runCurrent(); advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("the open, and no timer", 1, reads); assertEquals(1, rig.transport.attempts)
        page.cancel()
    }

    @Test fun `with the socket down for three attempts the visible page reads at most once a minute, and stops when it is back`() = runTest {
        val rig = rig { control, _ ->
            if (testScheduler.currentTime < 600_000) FakeLiveTransport.fail(SessionFailure.Connection)
            else { FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control) }
        }
        val times = mutableListOf<Long>()
        val page = launch { watchOrganization(true, { rig.live }, streams) { times += testScheduler.currentTime } }
        runCurrent(); advanceTimeBy(300_000); runCurrent()
        assertTrue("one read a minute after the third failure: $times", times.size in 3..6)
        assertTrue("never faster than once a minute", times.zipWithNext { a, b -> b - a }.drop(1).all { it >= 57_000 })
        advanceTimeBy(400_000); runCurrent()
        assertFalse(rig.live.fallback.value)
        val back = times.size
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("nothing on a timer once the socket is back", back, times.size)
        page.cancel()
    }

    // ---- Nothing in the background ----

    @Test fun `with the app in the background no socket is opened and nothing is read`() = runTest {
        val rig = rig()
        rig.device.foreground.value = false
        var reads = 0
        val page = launch { watchOrganization(true, { rig.live }, streams) { reads++ } }
        runCurrent(); advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("only the page's own open read", 1, reads); assertEquals(0, rig.transport.attempts)
        page.cancel()
    }

    @Test fun `leaving the foreground closes the socket after ten seconds and starts no timer`() = runTest {
        val rig = rig()
        var reads = 0
        val page = launch { watchOrganization(true, { rig.live }, streams) { reads++ } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, rig.transport.active)
        rig.device.foreground.value = false
        advanceTimeBy(10_001); runCurrent()
        assertEquals(0, rig.transport.active); assertFalse(rig.live.fallback.value)
        val away = reads
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("not one read while away", away, reads); assertEquals(1, rig.transport.attempts)
        // Coming back connects again at once and the page catches up with one read.
        rig.device.foreground.value = true; runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, rig.transport.attempts); assertEquals(away + 1, reads)
        page.cancel()
    }
}
