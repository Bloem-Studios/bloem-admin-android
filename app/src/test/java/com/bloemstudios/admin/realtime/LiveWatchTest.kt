package com.bloemstudios.admin.realtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveWatchTest {
    private val tasks = setOf(LiveChannels.TASKS, LiveChannels.JOBS)

    private fun TestScope.connection(jitter: Double = 1.0, script: (suspend (LiveControl, Int) -> Unit)? = null): Pair<LiveConnection, FakeLiveTransport> {
        val transport = FakeLiveTransport { testScheduler.currentTime }
        script?.let { transport.script = it }
        return LiveConnection(testApi(), transport, backgroundScope, FakeEnvironment(), LiveConfig(), { jitter }) to transport
    }

    @Test fun `while the socket is connected nothing is read on a timer`() = runTest {
        val (live, transport) = connection()
        var reads = 0
        val screen = launch { live.watch(tasks) { reads++ } }
        runCurrent()
        assertEquals("the open", 1, reads)
        // The connection's snapshots land just after it and say where the server is: one catch-up read.
        advanceTimeBy(DEFAULT_MIN_GAP_MILLIS + 1_000); runCurrent()
        assertEquals(2, reads)
        // Ten minutes with a quiet, healthy socket: not one more read.
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(2, reads); assertEquals(1, transport.attempts); assertFalse(live.fallback.value)
        screen.cancel()
    }

    @Test fun `a change on a watched channel reads again and a change on another channel does not`() = runTest {
        val (live, transport) = connection()
        var reads = 0
        val screen = launch { live.watch(tasks, minGapMillis = 0) { reads++ } }
        runCurrent(); assertEquals("the open, and the connection's catch-up", 2, reads)
        transport.controls.last().onEvent(LiveChannels.TASKS); runCurrent(); assertEquals(3, reads)
        transport.controls.last().onEvent(LiveChannels.JOBS); runCurrent(); assertEquals(4, reads)
        transport.controls.last().onEvent(LiveChannels.SCANS); runCurrent(); assertEquals(4, reads)
        screen.cancel()
    }

    @Test fun `a burst of changes merges into one read and reads are spaced`() = runTest {
        val (live, transport) = connection()
        val times = mutableListOf<Long>()
        val screen = launch { live.watch(tasks) { times += testScheduler.currentTime } }
        runCurrent(); assertEquals(1, times.size)
        val control = transport.controls.last()
        // A scan sends a change every quarter second.
        repeat(40) { control.onEvent(LiveChannels.TASKS); advanceTimeBy(250); runCurrent() }
        advanceTimeBy(2_000); runCurrent()
        assertTrue("at least two seconds between reads: $times", times.zipWithNext { a, b -> b - a }.all { it >= DEFAULT_MIN_GAP_MILLIS })
        assertTrue("about one read per gap, not one per change: $times", times.size in 5..7)
        // The last change is never lost: a read follows it.
        assertTrue(times.last() >= 10_000)
        screen.cancel()
    }

    @Test fun `a change that arrives during a read is read again afterwards`() = runTest {
        val (live, transport) = connection()
        val release = CompletableDeferred<Unit>(); var reads = 0
        val screen = launch { live.watch(tasks, minGapMillis = 0) { if (++reads == 3) release.await() } }
        runCurrent(); assertEquals(2, reads)
        val control = transport.controls.last()
        control.onEvent(LiveChannels.TASKS); runCurrent(); assertEquals(3, reads)
        control.onEvent(LiveChannels.TASKS); control.onEvent(LiveChannels.TASKS); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals("the two that landed during the read are one more read", 4, reads)
        screen.cancel()
    }

    @Test fun `a reconnect reads once to catch up`() = runTest {
        val (live, transport) = connection(jitter = 0.0) { control, attempt ->
            FakeLiveTransport.accept(control)
            if (attempt == 1) { delay(5_000); FakeLiveTransport.fail(SessionFailure.Connection) } else FakeLiveTransport.quiet(control)
        }
        var reads = 0
        val screen = launch { live.watch(tasks, minGapMillis = 0) { reads++ } }
        runCurrent()
        // The open read, then the first connection's own snapshots (two channels, one cue each, merged).
        val afterOpen = reads
        advanceTimeBy(5_001); runCurrent()
        assertEquals(2, transport.attempts)
        assertEquals("the new connection's two snapshots are one more read", afterOpen + 1, reads)
        val settled = reads
        advanceTimeBy(5 * 60_000L); runCurrent(); assertEquals(settled, reads)
        screen.cancel()
    }

    @Test fun `with the socket down the screen reads at most once a minute and stops when it is back`() = runTest {
        // Never connects for the first ten minutes, then does.
        val (live, transport) = connection(jitter = 1.0) { control, _ ->
            if (testScheduler.currentTime < 600_000) FakeLiveTransport.fail(SessionFailure.Connection)
            else { FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control) }
        }
        val times = mutableListOf<Long>()
        val screen = launch { live.watch(tasks, minGapMillis = 0) { times += testScheduler.currentTime } }
        runCurrent()
        advanceTimeBy(300_000); runCurrent()
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue("fallback reads: $times", times.size in 4..6)
        assertTrue("never faster than once a minute: $gaps", gaps.all { it >= 60_000L - 3_000 })
        advanceTimeBy(400_000); runCurrent()
        assertFalse(live.fallback.value)
        val back = times.size
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("nothing is read on a timer once the socket is back", back, times.size)
        screen.cancel()
    }

    @Test fun `a channel the server does not offer is read on the slow timer while the socket is up`() = runTest {
        val (live, transport) = connection { control, _ ->
            FakeLiveTransport.accept(control, rejected = mapOf(LiveChannels.SCANS to "unknown_channel")); FakeLiveTransport.quiet(control)
        }
        var reads = 0
        val screen = launch { live.watch(setOf(LiveChannels.SCANS), minGapMillis = 0) { reads++ } }
        runCurrent(); assertEquals("the open; the refused channel has no snapshot to cue another", 1, reads)
        advanceTimeBy(125_000); runCurrent()
        assertEquals(3, reads); assertEquals(1, transport.attempts)
        screen.cancel()
    }

    private fun TestScope.refusing(channel: String) = connection { control, _ ->
        FakeLiveTransport.accept(control, rejected = mapOf(channel to "unknown_channel")); FakeLiveTransport.quiet(control)
    }

    @Test fun `a screen that had no timer before its channel has none when the channel is not offered`() = runTest {
        val (live, transport) = refusing(LiveChannels.SCANS)
        var reads = 0
        val screen = launch { live.watch(setOf(LiveChannels.SCANS), minGapMillis = 0, pollWhenNotOffered = false) { reads++ } }
        runCurrent(); advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("only the read on open", 1, reads); assertEquals(1, transport.attempts)
        screen.cancel()
    }

    @Test fun `with the socket down every screen falls back to the once-a-minute read whether or not it polls for a channel that is not offered`() = runTest {
        for (policy in listOf(false, true)) {
            val (live, _) = connection(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
            val times = mutableListOf<Long>()
            val start = testScheduler.currentTime
            val screen = launch { live.watch(setOf(LiveChannels.SCANS), minGapMillis = 0, pollWhenNotOffered = policy) { times += testScheduler.currentTime - start } }
            runCurrent(); advanceTimeBy(5 * 60_000L); runCurrent()
            val gaps = times.zipWithNext { a, b -> b - a }
            assertTrue("$policy: fallback reads $times", times.size in 4..6)
            assertTrue("$policy: never faster than once a minute: $gaps", gaps.all { it >= 60_000L - 3_000 })
            screen.cancel(); runCurrent()
        }
    }

    @Test fun `a channel the server does offer is never polled`() = runTest {
        val (live, _) = connection()
        var reads = 0
        val screen = launch { live.watch(setOf(LiveChannels.SCANS), minGapMillis = 0) { reads++ } }
        runCurrent(); assertEquals("the open, and the connection's catch-up", 2, reads)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(2, reads)
        screen.cancel()
    }

    @Test fun `leaving the screen releases the socket after ten seconds`() = runTest {
        val (live, transport) = connection()
        val screen = launch { live.watch(tasks) { } }
        runCurrent(); assertEquals(1, transport.active)
        screen.cancel(); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(0, transport.active)
    }

    @Test fun `a failing read does not end the screen's watching`() = runTest {
        val (live, transport) = connection()
        var reads = 0
        val screen = launch { live.watch(tasks, minGapMillis = 0) { if (++reads == 1) throw java.io.IOException("offline") } }
        runCurrent(); transport.controls.last().onEvent(LiveChannels.TASKS); runCurrent()
        assertEquals(3, reads); assertTrue(screen.isActive)
        screen.cancel()
    }

    @Test fun `several servers share one read and each holds its own socket only while watched`() = runTest {
        val (one, t1) = connection(); val (two, t2) = connection()
        var reads = 0
        val screen = launch { watchLive(listOf(one, two), setOf(LiveChannels.SCANS), minGapMillis = 0) { reads++ } }
        runCurrent()
        assertTrue("one open read for both servers, then their catch-ups; not a read per server each: $reads", reads in 2..3)
        assertEquals(1, t1.active); assertEquals(1, t2.active)
        val base = reads
        t2.controls.last().onEvent(LiveChannels.SCANS); runCurrent(); assertEquals(base + 1, reads)
        t1.controls.last().onEvent(LiveChannels.SCANS); runCurrent(); assertEquals(base + 2, reads)
        screen.cancel(); advanceTimeBy(10_001); runCurrent()
        assertEquals(0, t1.active); assertEquals(0, t2.active)
    }

    @Test fun `with no server the screen reads once and holds nothing`() = runTest {
        var reads = 0
        val screen = launch { watchLive(emptyList(), setOf(LiveChannels.SCANS)) { reads++ } }
        runCurrent(); advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, reads); screen.cancel()
    }

    @Test fun `a screen that has just read itself can skip the read on open`() = runTest {
        val (live, transport) = connection()
        var reads = 0
        val screen = launch { live.watch(tasks, minGapMillis = 0, readOnOpen = false) { reads++ } }
        runCurrent(); assertEquals("only the connection's catch-up, no read of its own", 1, reads)
        transport.controls.last().onEvent(LiveChannels.TASKS); runCurrent(); assertEquals(2, reads)
        screen.cancel()
    }
}
