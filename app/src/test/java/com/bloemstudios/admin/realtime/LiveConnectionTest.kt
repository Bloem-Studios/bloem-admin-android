package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConnectionTest {
    private val sessions = setOf(LiveChannels.SESSIONS)

    private class Rig(
        val api: com.bloemstudios.admin.network.ApiClient,
        val transport: FakeLiveTransport,
        val env: FakeEnvironment,
        val live: LiveConnection,
    )

    private fun TestScope.rig(jitter: Double = 1.0, script: (suspend (LiveControl, Int) -> Unit)? = null): Rig {
        val transport = FakeLiveTransport { testScheduler.currentTime }
        script?.let { transport.script = it }
        val env = FakeEnvironment()
        val api = testApi()
        return Rig(api, transport, env, LiveConnection(api, transport, backgroundScope, env, LiveConfig(), { jitter }))
    }

    @Test fun `no socket opens until a screen asks and it closes ten seconds after the last one leaves`() = runTest {
        val r = rig()
        runCurrent(); advanceTimeBy(60_000); assertEquals(0, r.transport.attempts)
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        assertEquals(1, r.transport.attempts); assertEquals(LiveStatus.Live, r.live.status.value)
        screen.cancel(); runCurrent()
        advanceTimeBy(9_999); runCurrent(); assertEquals(1, r.transport.active); assertEquals(LiveStatus.Live, r.live.status.value)
        advanceTimeBy(2); runCurrent(); assertEquals(0, r.transport.active); assertEquals(LiveStatus.Idle, r.live.status.value)
        advanceTimeBy(120_000); runCurrent(); assertEquals(1, r.transport.attempts)
    }

    @Test fun `moving between screens inside the grace period keeps the same connection`() = runTest {
        val r = rig()
        val a = launch { r.live.retain(sessions) }; runCurrent()
        a.cancel(); runCurrent(); advanceTimeBy(6_000); runCurrent()
        val b = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, r.transport.attempts); assertEquals(1, r.transport.active)
        b.cancel()
    }

    @Test fun `leaving the foreground closes after ten seconds and coming back reconnects at once`() = runTest {
        val r = rig()
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        r.env.foreground.value = false; runCurrent()
        advanceTimeBy(9_999); runCurrent(); assertEquals(1, r.transport.active)
        advanceTimeBy(2); runCurrent(); assertEquals(0, r.transport.active)
        advanceTimeBy(300_000); runCurrent(); assertEquals(1, r.transport.attempts)
        r.env.foreground.value = true; runCurrent()
        assertEquals(2, r.transport.attempts); assertEquals(LiveStatus.Live, r.live.status.value)
        screen.cancel()
    }

    @Test fun `a short trip to the background keeps the connection`() = runTest {
        val r = rig()
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        r.env.foreground.value = false; runCurrent(); advanceTimeBy(8_000); runCurrent()
        r.env.foreground.value = true; runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, r.transport.attempts); assertEquals(1, r.transport.active)
        screen.cancel()
    }

    @Test fun `subscriptions are counted per caller and the wanted set is their union`() = runTest {
        val r = rig()
        val tasksOnly = launch { r.live.retain(setOf(LiveChannels.TASKS)) }; runCurrent()
        val control = r.transport.controls.single()
        assertEquals(setOf(LiveChannels.TASKS), control.wanted())
        val both = launch { r.live.retain(setOf(LiveChannels.TASKS, LiveChannels.SCANS)) }; runCurrent()
        assertEquals(setOf(LiveChannels.TASKS, LiveChannels.SCANS), control.wanted())
        assertEquals(2, r.live.holdersOf(LiveChannels.TASKS)); assertEquals(1, r.live.holdersOf(LiveChannels.SCANS))
        tasksOnly.cancel(); runCurrent()
        assertEquals(1, r.live.holdersOf(LiveChannels.TASKS)); assertEquals(setOf(LiveChannels.TASKS, LiveChannels.SCANS), control.wanted())
        both.cancel(); runCurrent()
        assertEquals(emptySet<String>(), control.wanted()); assertEquals(1, r.transport.active)
        advanceTimeBy(10_001); runCurrent(); assertEquals(0, r.transport.active)
        assertEquals(1, r.transport.attempts)
    }

    @Test fun `a second screen asking for the same channel does not reconnect`() = runTest {
        val r = rig()
        val a = launch { r.live.retain(sessions) }; runCurrent()
        val b = launch { r.live.retain(sessions) }; runCurrent()
        assertEquals(1, r.transport.attempts); a.cancel(); runCurrent(); assertEquals(1, r.transport.active); b.cancel()
    }

    @Test fun `backoff doubles to a cap of sixty seconds and each wait is jittered`() = runTest {
        val r = rig(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(400_000); runCurrent()
        val gaps = r.transport.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps.take(8))
        screen.cancel()

        val half = rig(jitter = 0.5) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val other = launch { half.live.retain(sessions) }; runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L), half.transport.times.zipWithNext { a, b -> b - a }.take(4))
        other.cancel()
    }

    @Test fun `a connection that held resets the backoff`() = runTest {
        val r = rig(jitter = 1.0) { control, attempt ->
            when {
                attempt <= 4 -> FakeLiveTransport.fail(SessionFailure.Connection)
                attempt == 5 -> { FakeLiveTransport.accept(control); delay(30_000); FakeLiveTransport.fail(SessionFailure.Connection) }
                else -> FakeLiveTransport.fail(SessionFailure.Connection)
            }
        }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(200_000); runCurrent()
        val gaps = r.transport.times.zipWithNext { a, b -> b - a }
        // 1, 2, 4, 8 s before the connection that held, which then lasted 30 s; the wait after it starts over at 1 s
        // (not the 16 s the next doubling would have made), and the failures after that count up from one again.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), gaps.take(4))
        assertEquals(30_000L + 1_000L, gaps[4])
        assertEquals(2_000L, gaps[5])
        screen.cancel()
    }

    @Test fun `three failed connects in a row turn the fallback on and a good connection turns it off`() = runTest {
        val r = rig(jitter = 1.0) { control, attempt ->
            if (attempt <= 3) FakeLiveTransport.fail(SessionFailure.Connection) else { FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control) }
        }
        val screen = launch { r.live.retain(sessions) }
        runCurrent(); assertFalse(r.live.fallback.value)            // 1 failed
        advanceTimeBy(1_000); runCurrent(); assertFalse(r.live.fallback.value)   // 2 failed
        advanceTimeBy(2_000); runCurrent(); assertTrue(r.live.fallback.value)    // 3 failed
        assertEquals(3, r.transport.attempts)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(4, r.transport.attempts); assertFalse(r.live.fallback.value); assertEquals(LiveStatus.Live, r.live.status.value)
        screen.cancel()
    }

    @Test fun `the watchdog treats sixty silent seconds as a dead socket and a sign of life restarts the count`() = runTest {
        val r = rig(jitter = 0.0)
        r.transport.keepAliveMillis = null   // a connection that accepted and then says nothing at all
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        val first = r.transport.controls.single()
        advanceTimeBy(59_000); first.onActivity(); runCurrent()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, r.transport.attempts); assertEquals(LiveStatus.Live, r.live.status.value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, r.transport.attempts)   // 118 s in: 60 s after the last sign of life
        assertEquals(1, r.transport.active)
        screen.cancel()
    }

    @Test fun `a network change reconnects at once and so does one during a backoff`() = runTest {
        val r = rig(jitter = 1.0)
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(5_000)
        r.env.network.tryEmit(Unit); runCurrent()
        assertEquals(2, r.transport.attempts); assertEquals(5_000L, r.transport.times.last()); assertEquals(1, r.transport.active)
        screen.cancel(); advanceTimeBy(10_001); runCurrent()

        val down = rig(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val start = testScheduler.currentTime
        val other = launch { down.live.retain(sessions) }; runCurrent()
        advanceTimeBy(1_000); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(2, down.transport.attempts)            // the next try is 2 s away
        down.env.network.tryEmit(Unit); runCurrent()
        assertEquals(3, down.transport.attempts); assertEquals(start + 1_500L, down.transport.times.last())
        other.cancel()
    }

    @Test fun `coming back to the foreground cuts a backoff short`() = runTest {
        val r = rig(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(3_000); runCurrent()   // attempts at 0, 1 and 3 s; the next is 4 s later
        assertEquals(3, r.transport.attempts)
        r.env.foreground.value = false; runCurrent(); advanceTimeBy(1_000); runCurrent()
        r.env.foreground.value = true; runCurrent()
        assertEquals(4, r.transport.attempts); assertEquals(4_000L, r.transport.times.last())
        screen.cancel()
    }

    @Test fun `the first snapshot of a connection cues its channel once and a later subscribe does not cue it again`() = runTest {
        val r = rig()
        val tasks = r.live.signal(LiveChannels.TASKS)
        val screen = launch { r.live.retain(setOf(LiveChannels.TASKS)) }; runCurrent()
        assertEquals(1L, tasks.value)
        val control = r.transport.controls.single()
        // A second screen adds a channel: the server answers again with a snapshot for every channel it holds.
        val more = launch { r.live.retain(setOf(LiveChannels.SCANS)) }; runCurrent()
        control.onSubscribed(setOf(LiveChannels.TASKS, LiveChannels.SCANS), emptyMap())
        control.onSnapshot(LiveChannels.TASKS); control.onSnapshot(LiveChannels.SCANS)
        assertEquals(1L, tasks.value); assertEquals(1L, r.live.signal(LiveChannels.SCANS).value)
        control.onEvent(LiveChannels.TASKS); assertEquals(2L, tasks.value)
        screen.cancel(); more.cancel()
    }

    @Test fun `a reconnect cues every channel again so a screen catches up on what it missed`() = runTest {
        val r = rig(jitter = 0.0) { control, attempt ->
            FakeLiveTransport.accept(control)
            if (attempt == 1) { delay(1_000); FakeLiveTransport.fail(SessionFailure.Connection) } else FakeLiveTransport.quiet(control)
        }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        assertEquals(1L, r.live.signal(LiveChannels.SESSIONS).value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, r.transport.attempts); assertEquals(2L, r.live.signal(LiveChannels.SESSIONS).value)
        screen.cancel()
    }

    @Test fun `what a finished connection still says is dropped`() = runTest {
        val r = rig(jitter = 1.0) { control, attempt ->
            FakeLiveTransport.accept(control)
            if (attempt == 1) FakeLiveTransport.fail(SessionFailure.Connection) else FakeLiveTransport.quiet(control)
        }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        val old = r.transport.controls.first()
        val before = r.live.signal(LiveChannels.SESSIONS).value
        old.onEvent(LiveChannels.SESSIONS); old.onSnapshot(LiveChannels.SESSIONS)
        assertEquals(before, r.live.signal(LiveChannels.SESSIONS).value)
        screen.cancel()
    }

    @Test fun `a refused login stops the retries until another login replaces it`() = runTest {
        val r = rig { _, _ -> FakeLiveTransport.fail(SessionFailure.SignInRequired) }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        assertEquals(LiveStatus.SignInRequired, r.live.status.value); assertFalse(r.live.fallback.value)
        advanceTimeBy(600_000); runCurrent(); assertEquals(1, r.transport.attempts)
        r.api.refreshAccessToken(AccessTokenSnapshot("same-principal", 1)); runCurrent()
        assertEquals(1, r.transport.attempts)
        r.api.replaceAuthenticatedPrincipal("another login"); runCurrent()
        assertEquals(2, r.transport.attempts)
        screen.cancel()
    }

    @Test fun `a server with no events socket turns the fallback on without retrying`() = runTest {
        val r = rig { _, _ -> FakeLiveTransport.fail(SessionFailure.Unsupported) }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        assertEquals(LiveStatus.Unavailable, r.live.status.value); assertTrue(r.live.fallback.value)
        advanceTimeBy(600_000); runCurrent(); assertEquals(1, r.transport.attempts)
        screen.cancel(); advanceTimeBy(10_001); runCurrent()
        assertEquals(LiveStatus.Idle, r.live.status.value); assertFalse(r.live.fallback.value)
    }

    @Test fun `access changed reconnects at once the first time and backs off a repeat`() = runTest {
        val r = rig(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.AccessChanged) }
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0L, r.transport.times[0]); assertEquals(0L, r.transport.times[1])
        assertTrue(r.transport.times.zipWithNext { a, b -> b - a }.drop(1).all { it >= 1_000L })
        screen.cancel()
    }

    @Test fun `restart drops the connection and connects again after the usual wait`() = runTest {
        val r = rig(jitter = 1.0)
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        r.live.restart(); runCurrent()
        assertEquals(LiveStatus.Reconnecting, r.live.status.value)
        advanceTimeBy(1_001); runCurrent(); assertEquals(2, r.transport.attempts)
        screen.cancel()
    }

    @Test fun `closing the api closes the connection`() = runTest {
        val r = rig()
        val screen = launch { r.live.retain(sessions) }; runCurrent()
        r.api.close(); runCurrent()
        assertEquals(0, r.transport.active); assertEquals(LiveStatus.Idle, r.live.status.value)
        screen.cancel()
    }
}
