package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.streams.AdminSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionsRepositoryTest {
    private val row = AdminSession("s", "user-1", "u", "profile-1", "movie", "movie", "direct", 0.0, null, false)
    private val sessions = LiveChannels.SESSIONS

    /** A shared socket whose server is scripted; [up] and [cue] are what that server would say. */
    private class Rig(val api: ApiClient, val transport: FakeLiveTransport, val live: LiveConnection, val env: FakeEnvironment) {
        fun cue() { transport.controls.last().onEvent(LiveChannels.SESSIONS) }
        fun up() = FakeLiveTransport.accept(transport.controls.last())
    }

    private fun TestScope.rig(api: ApiClient = testApi(), jitter: Double = 1.0, script: (suspend (LiveControl, Int) -> Unit)? = null): Rig {
        val transport = FakeLiveTransport { testScheduler.currentTime }
        // By default a connection is up and quiet; the tests say when the server speaks.
        transport.script = script ?: { control, _ -> FakeLiveTransport.quiet(control) }
        val env = FakeEnvironment()
        return Rig(api, transport, LiveConnection(api, transport, backgroundScope, env, LiveConfig(), { jitter }), env)
    }

    private fun TestScope.repo(r: Rig, read: suspend () -> List<AdminSession>) = LiveSessionsRepository(r.api, r.live, read, backgroundScope)

    @Test fun `consumers share one attempt and the socket closes ten seconds after the last one`() = runTest {
        val r = rig(); val repo = repo(r) { emptyList() }
        val a = launch { repo.observe() }; runCurrent()
        assertEquals(1, r.transport.active)
        val b = launch { repo.observe() }; runCurrent(); assertEquals(1, r.transport.attempts)
        a.cancel(); runCurrent(); assertEquals(1, r.transport.active)
        b.cancel(); runCurrent(); assertEquals(1, r.transport.active)
        advanceTimeBy(10_001); runCurrent(); assertEquals(0, r.transport.active)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, r.transport.attempts)
        val c = launch { repo.observe() }; runCurrent(); assertEquals(2, r.transport.attempts)
        assertEquals(1, r.transport.active); c.cancel(); repo.close(); r.api.close()
    }

    @Test fun `snapshot is a cue to read the full list and a late older read cannot replace it`() = runTest {
        val pending = CompletableDeferred<List<AdminSession>>(); var reads = 0
        val rows = mutableListOf(row)
        val r = rig { control, _ -> FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control) }
        val repo = repo(r) { if (++reads == 1) pending.await() else rows.toList() }
        val owner = launch { repo.observe() }; runCurrent()
        // The snapshot arrived while the first read is out: its cue read queues behind it.
        assertNull(repo.sessions.value); assertNotEquals(RealtimeState.Live, repo.state.value)
        pending.complete(emptyList()); runCurrent()
        assertEquals(listOf(row), repo.sessions.value); assertEquals(RealtimeState.Live, repo.state.value)
        advanceTimeBy(10 * 60_000); runCurrent(); assertEquals("no timer while live: the open read and the cue read", 2, reads)
        rows.clear(); r.cue(); runCurrent()
        assertEquals(emptyList<AdminSession>(), repo.sessions.value); assertEquals(3, reads)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `live is published only after the full read finishes`() = runTest {
        val gate = CompletableDeferred<List<AdminSession>>(); var reads = 0
        val r = rig()
        val repo = repo(r) { if (++reads == 1) emptyList() else gate.await() }
        val owner = launch { repo.observe() }; runCurrent()
        r.up(); runCurrent()
        assertNotEquals(RealtimeState.Live, repo.state.value)
        gate.complete(listOf(row)); runCurrent()
        assertEquals(RealtimeState.Live, repo.state.value); assertEquals(listOf(row), repo.sessions.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `failed read after a cue ends the attempt and reconnects instead of going live`() = runTest {
        var reads = 0
        val r = rig()
        val repo = repo(r) { if (++reads == 1) emptyList() else throw ApiError(503, "down") }
        val owner = launch { repo.observe() }; runCurrent()
        r.up(); runCurrent()
        assertNotEquals(RealtimeState.Live, repo.state.value)
        assertEquals(RealtimeState.Reconnecting, repo.state.value); assertEquals(SessionFailure.Connection, repo.error.value)
        advanceTimeBy(1_001); runCurrent(); assertEquals(2, r.transport.attempts)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `cue read surfaces a forbidden answer as terminal`() = runTest {
        val api = testApi(); var gates = 0; api.onTerminalAuthorityLost = { gates++ }
        var reads = 0
        val r = rig(api)
        val repo = repo(r) { if (++reads == 1) emptyList() else throw ApiError(403, "no") }
        val owner = launch { repo.observe() }; runCurrent()
        r.up(); runCurrent()
        assertEquals(RealtimeState.SignInRequired, repo.state.value); assertNull(repo.sessions.value); assertEquals(1, gates)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `no socket on the server reads on the slow timer only, and does not retry the socket`() = runTest {
        var reads = 0
        val r = rig { _, _ -> FakeLiveTransport.fail(SessionFailure.Unsupported) }
        val repo = repo(r) { reads++; emptyList() }
        val owner = launch { repo.observe() }; runCurrent(); assertEquals(1, reads)
        advanceTimeBy(59_000); runCurrent(); assertEquals(1, reads)
        advanceTimeBy(1_100); runCurrent(); assertEquals(2, reads)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, reads)
        assertEquals(1, r.transport.attempts); assertEquals(RealtimeState.Unavailable, repo.state.value)
        assertEquals(SessionFailure.Unsupported, repo.error.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `a server without a sessions channel is polled slowly while the socket stays up`() = runTest {
        var reads = 0
        val r = rig { control, _ ->
            FakeLiveTransport.accept(control, rejected = mapOf(LiveChannels.SESSIONS to "unknown_channel"))
            FakeLiveTransport.quiet(control)
        }
        val repo = repo(r) { reads++; emptyList() }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(RealtimeState.Unavailable, repo.state.value); assertEquals(SessionFailure.Unsupported, repo.error.value)
        advanceTimeBy(60_100); runCurrent(); assertEquals(2, reads)
        assertEquals(1, r.transport.attempts)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `a refused sessions channel is a refused login`() = runTest {
        val api = testApi(); var gates = 0; api.onTerminalAuthorityLost = { gates++ }
        val r = rig(api) { control, _ ->
            FakeLiveTransport.accept(control, rejected = mapOf(LiveChannels.SESSIONS to "forbidden"))
            FakeLiveTransport.quiet(control)
        }
        val repo = repo(r) { emptyList() }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(RealtimeState.SignInRequired, repo.state.value); assertEquals(1, gates)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `refresh callers share an in flight read`() = runTest {
        val pending = CompletableDeferred<List<AdminSession>>(); var reads = 0
        val r = rig(); val repo = repo(r) { reads++; pending.await() }
        val owner = launch { repo.observe() }; runCurrent()
        val refreshes = List(4) { launch { repo.refresh() } }; runCurrent(); assertEquals(1, reads)
        pending.complete(emptyList()); runCurrent(); assertTrue(refreshes.all { it.isCompleted })
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `a refused login clears data and stops automatic work`() = runTest {
        val api = testApi(); var gateCalls = 0; api.onTerminalAuthorityLost = { gateCalls++ }
        var reads = 0
        val r = rig(api) { _, _ -> FakeLiveTransport.fail(SessionFailure.SignInRequired) }
        val repo = repo(r) { reads++; emptyList() }
        val owner = launch { repo.observe() }; runCurrent()
        assertNull(repo.sessions.value); assertEquals(RealtimeState.SignInRequired, repo.state.value); assertEquals(1, gateCalls)
        val initial = reads; advanceTimeBy(90_000); runCurrent(); assertEquals(initial, reads)
        repo.refresh(); assertEquals(initial, reads); owner.cancel(); repo.close(); api.close()
    }

    @Test fun `access changed reconnects at once with a new attempt and is not a sign in failure`() = runTest {
        val api = testApi(); var gateCalls = 0; api.onTerminalAuthorityLost = { gateCalls++ }
        val r = rig(api) { control, attempt ->
            FakeLiveTransport.accept(control)
            if (attempt == 1) FakeLiveTransport.fail(SessionFailure.AccessChanged) else FakeLiveTransport.quiet(control)
        }
        val repo = repo(r) { listOf(row) }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(listOf(0L, 0L), r.transport.times)
        assertEquals(RealtimeState.Live, repo.state.value); assertNull(repo.error.value); assertEquals(0, gateCalls)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `a released owner is not read for and a new owner starts afresh`() = runTest {
        var rows = emptyList<AdminSession>(); var reads = 0
        val r = rig(); val repo = repo(r) { reads++; rows }
        val a = launch { repo.observe() }; runCurrent()
        a.cancel(); runCurrent()
        val before = reads
        r.cue(); runCurrent()
        assertEquals("a cue after the last screen left starts no read", before, reads)
        val b = launch { repo.observe() }; runCurrent()
        rows = listOf(row); r.cue(); runCurrent()
        assertEquals(listOf(row), repo.sessions.value)
        repo.close(); runCurrent(); assertNull(repo.sessions.value)
        b.cancel(); r.api.close()
    }

    @Test fun `principal replacement clears synchronously and rejects old suspended publication`() = runTest {
        val api = testApi(); val pending = CompletableDeferred<List<AdminSession>>()
        val r = rig(api) { _, _ ->
            val epoch = api.currentPrincipalEpoch
            coroutineScope {
                launch { api.principalEpoch.first { it != epoch }; FakeLiveTransport.fail(SessionFailure.SignInRequired) }
                awaitCancellation()
            }
        }
        val repo = repo(r) { withContext(NonCancellable) { pending.await() } }
        val owner = launch { repo.observe() }; runCurrent()
        api.replaceAuthenticatedPrincipal("replacement")
        assertNull(repo.sessions.value)
        runCurrent(); assertNull(repo.sessions.value)
        pending.complete(listOf(row)); runCurrent()
        assertEquals(2, r.transport.attempts)
        assertNotEquals("a replaced login is not a refused one", RealtimeState.SignInRequired, repo.state.value)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `rest forbidden is terminal and the socket is released`() = runTest {
        val api = testApi(); var gates = 0; api.onTerminalAuthorityLost = { gates++ }
        val r = rig(api); val repo = repo(r) { throw ApiError(403, "fixture") }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(RealtimeState.SignInRequired, repo.state.value); assertEquals(1, gates)
        advanceTimeBy(10_001); runCurrent(); assertEquals(0, r.transport.active); owner.cancel(); repo.close(); api.close()
    }

    @Test fun `what an old connection says after a reconnect is dropped`() = runTest {
        var rows = emptyList<AdminSession>(); var reads = 0
        val r = rig(jitter = 0.0) { control, attempt ->
            FakeLiveTransport.accept(control)
            if (attempt == 1) FakeLiveTransport.fail(SessionFailure.Connection) else FakeLiveTransport.quiet(control)
        }
        val repo = repo(r) { reads++; rows }
        val owner = launch { repo.observe() }; runCurrent(); advanceTimeBy(1); runCurrent()
        assertEquals(2, r.transport.attempts)
        val old = r.transport.controls.first(); val before = reads
        rows = listOf(row)
        old.onEvent(LiveChannels.SESSIONS); old.onSnapshot(LiveChannels.SESSIONS); runCurrent()
        assertEquals("the first connection is over and cannot cue a read", before, reads)
        r.cue(); runCurrent()
        assertEquals(listOf(row), repo.sessions.value); assertEquals(RealtimeState.Live, repo.state.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `failed connects fall back to the slow timer and no read survives the last owner`() = runTest {
        var reads = 0; var canceled = 0
        val r = rig(jitter = 1.0) { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val repo = repo(r) {
            reads++
            if (reads == 3) try { awaitCancellation() } finally { canceled++ }
            emptyList()
        }
        val owner = launch { repo.observe() }; runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals("no timed read before the third failed connect has switched the fallback on, and not before sixty seconds after", 1, reads)
        assertTrue(r.live.fallback.value)
        advanceTimeBy(40_000); runCurrent(); assertEquals(2, reads)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, reads)
        owner.cancel(); runCurrent(); assertEquals(1, canceled)
        val attempts = r.transport.attempts; advanceTimeBy(90_000); runCurrent(); assertEquals(attempts, r.transport.attempts)
        assertEquals(3, reads)
        repo.close(); r.api.close()
    }

    @Test fun `api close synchronously clears cache and same authority rotation keeps connection`() = runTest {
        var rows = emptyList<AdminSession>()
        val r = rig(); val repo = repo(r) { rows }
        val owner = launch { repo.observe() }; runCurrent(); rows = listOf(row); r.up(); r.cue(); runCurrent()
        r.api.refreshAccessToken(com.bloemstudios.admin.auth.AccessTokenSnapshot("rotated", 3))
        runCurrent(); assertEquals(1, r.transport.attempts); assertEquals(listOf(row), repo.sessions.value)
        r.api.close(); assertNull(repo.sessions.value); runCurrent(); assertEquals(0, r.transport.active)
        assertSame(r.api.liveSessions, r.api.liveSessions); assertNull(r.api.liveSessions.sessions.value)
        owner.cancel(); repo.close()
    }

    @Test fun `rapid owners cannot bypass a canceled predecessor still closing`() = runTest {
        val release = CompletableDeferred<Unit>(); var active = 0; var maximum = 0; var reads = 0
        val r = rig()
        val repo = repo(r) {
            reads++; active++; maximum = maxOf(maximum, active)
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() }; active-- }
        }
        val a = launch { repo.observe() }; runCurrent(); a.cancel(); runCurrent()
        val b = launch { repo.observe() }; runCurrent(); b.cancel(); runCurrent()
        val c = launch { repo.observe() }; runCurrent()
        val beforeRelease = reads
        release.complete(Unit); runCurrent()
        assertEquals(1, beforeRelease); assertEquals(1, maximum); assertEquals(2, reads)
        c.cancel(); repo.close(); r.api.close()
    }

    @Test fun `late rest after last cancellation cannot fill the privileged cache`() = runTest {
        val pending = CompletableDeferred<List<AdminSession>>()
        val r = rig(); val repo = repo(r) { withContext(NonCancellable) { pending.await() } }
        val owner = launch { repo.observe() }; runCurrent(); owner.cancel(); runCurrent()
        pending.complete(listOf(row)); runCurrent(); assertNull(repo.sessions.value)
        repo.close(); r.api.close()
    }

    @Test fun `terminal epoch ignores late cues and only replacement authority restarts`() = runTest {
        val api = testApi()
        var rows = emptyList<AdminSession>()
        val r = rig(api) { control, attempt ->
            if (attempt == 1) FakeLiveTransport.fail(SessionFailure.SignInRequired)
            FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control)
        }
        val repo = repo(r) { rows }
        val a = launch { repo.observe() }; runCurrent()
        val stale = r.transport.controls.first()
        rows = listOf(row); stale.onEvent(LiveChannels.SESSIONS); runCurrent(); assertNull(repo.sessions.value)
        a.cancel(); runCurrent(); val b = launch { repo.observe() }; runCurrent(); assertEquals(1, r.transport.attempts)
        assertEquals(RealtimeState.SignInRequired, repo.state.value)
        api.replaceAuthenticatedPrincipal(com.bloemstudios.admin.auth.AccessTokenSnapshot("new authority", 5))
        assertNull(repo.sessions.value); runCurrent(); assertEquals(2, r.transport.attempts)
        assertEquals(listOf(row), repo.sessions.value)
        b.cancel(); repo.close(); api.close()
    }

    @Test fun `queued terminal notification accepts same principal token rotation through real binding`() = runTest {
        queuedTerminalBinding(replacePrincipal = false)
    }
    @Test fun `queued terminal notification rejects replacement principal through real binding`() = runTest {
        queuedTerminalBinding(replacePrincipal = true)
    }
    private suspend fun TestScope.queuedTerminalBinding(replacePrincipal: Boolean) {
        val api = testApi()
        val gate = com.bloemstudios.admin.servers.AdminSessionViewModel()
        val binding = com.bloemstudios.admin.servers.AdminUnauthorizedBinding(
            api, { api.accessTokenSnapshotForTest() }, { gate.reportAdminUnauthorized("fixture") })
        val events = mutableListOf<String>()
        val r = rig(api) { control, attempt ->
            if (attempt > 1) { FakeLiveTransport.accept(control); FakeLiveTransport.quiet(control) }
            FakeLiveTransport.fail(SessionFailure.SignInRequired)
        }
        val repo = repo(r) { emptyList() }
        // Queue the rotation ahead of the repository's separately scheduled terminal notification.
        backgroundScope.launch {
            repo.state.first { it == RealtimeState.SignInRequired }
            assertNull(gate.sessionExpiredReason("fixture"))
            events += "rotation"
            if (replacePrincipal) api.replaceAuthenticatedPrincipal("new principal")
            else api.refreshAccessToken(com.bloemstudios.admin.auth.AccessTokenSnapshot("new token", 1))
        }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(listOf("rotation"), events)
        if (replacePrincipal) assertNull(gate.sessionExpiredReason("fixture"))
        else { assertNotNull(gate.sessionExpiredReason("fixture")); assertEquals(RealtimeState.SignInRequired, repo.state.value) }
        owner.cancel(); binding.close(); repo.close(); api.close()
    }

    @Test fun `confirmed action returns normally when background cancels its reconciliation`() = runTest {
        var posts = 0
        val api = ApiClient("https://example.com", "fixture", MockEngine { request ->
            assertEquals(io.ktor.http.HttpMethod.Post, request.method); posts++; respond("")
        })
        val r = rig(api); val repo = repo(r) { awaitCancellation() }
        val owner = launch { repo.observe() }; runCurrent()
        var confirmed = false; var continued = false
        val reconciling = CompletableDeferred<Unit>()
        val action = launch { api.post("/api/fixture/action"); confirmed = true; reconciling.complete(Unit); repo.refresh(); continued = true }
        reconciling.await(); runCurrent(); assertFalse(action.isCompleted)
        owner.cancel(); runCurrent()
        assertTrue(confirmed); assertEquals(1, posts); assertTrue(continued); assertFalse(action.isCancelled)
        assertEquals(RealtimeState.Unavailable, repo.state.value)
        repo.close(); api.close()
    }

    @Test fun `confirmed action returns normally when terminal authority cancels reconciliation`() = runTest {
        var posts = 0
        val api = ApiClient("https://example.com", "fixture", MockEngine { request ->
            assertEquals(io.ktor.http.HttpMethod.Post, request.method); posts++; respond("")
        })
        val deny = CompletableDeferred<Unit>()
        val r = rig(api); val repo = repo(r) { deny.await(); throw ApiError(403, "fixture") }
        val owner = launch { repo.observe() }; runCurrent()
        var confirmed = false; var continued = false
        val reconciling = CompletableDeferred<Unit>()
        val action = launch { api.post("/api/fixture/action"); confirmed = true; reconciling.complete(Unit); repo.refresh(); continued = true }
        reconciling.await(); runCurrent(); assertFalse(action.isCompleted)
        deny.complete(Unit); runCurrent()
        assertTrue(confirmed); assertEquals(1, posts); assertTrue(continued); assertFalse(action.isCancelled)
        assertEquals(RealtimeState.SignInRequired, repo.state.value); assertNull(repo.sessions.value)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `actual refresh caller cancellation still propagates without canceling shared read`() = runTest {
        val read = CompletableDeferred<List<AdminSession>>()
        val r = rig(); val repo = repo(r) { read.await() }
        val owner = launch { repo.observe() }; runCurrent()
        var continued = false
        val action = launch { repo.refresh(); continued = true }
        runCurrent(); action.cancel(); runCurrent(); read.complete(listOf(row)); runCurrent()
        assertTrue(action.isCancelled); assertFalse(continued); assertEquals(listOf(row), repo.sessions.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `confirmed mutation fences an old read and waits for a fresh read`() = runTest {
        var posts = 0; var reads = 0
        val api = ApiClient("https://fixture.invalid", null, MockEngine { posts++; respond("") })
        val old = CompletableDeferred<List<AdminSession>>()
        val fresh = CompletableDeferred<List<AdminSession>>()
        val r = rig(api) { _, _ -> FakeLiveTransport.fail(SessionFailure.Unsupported) }
        val repo = repo(r) { if (++reads == 1) old.await() else fresh.await() }
        val owner = launch { repo.observe() }; runCurrent()
        assertEquals(1, reads)
        api.post("/api/fixture/stop"); assertEquals(1, posts)
        val action = launch { repo.reconcileAfterMutation() }; runCurrent()
        old.complete(listOf(row)); runCurrent()
        assertNull("Pre-mutation rows cannot publish after confirmed mutation", repo.sessions.value)
        assertEquals(2, reads); assertFalse(action.isCompleted)
        fresh.complete(emptyList()); runCurrent()
        assertTrue(action.isCompleted); assertEquals(emptyList<AdminSession>(), repo.sessions.value)
        owner.cancel(); repo.close(); api.close()
    }

    @Test fun `a replacement cue during mutation reconciliation reads after it and keeps the newer rows`() = runTest {
        var reads = 0
        val pending = CompletableDeferred<List<AdminSession>>()
        val r = rig(); val repo = repo(r) { when (++reads) { 1 -> emptyList(); 2 -> pending.await(); else -> listOf(row) } }
        val owner = launch { repo.observe() }; runCurrent()
        val action = launch { repo.reconcileAfterMutation() }; runCurrent()
        r.up(); runCurrent()
        pending.complete(emptyList()); runCurrent()
        assertTrue(action.isCompleted); assertEquals(3, reads)
        assertEquals(listOf(row), repo.sessions.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `mutation barrier coalesces ordinary refreshes and returns normally on owner loss`() = runTest {
        var reads = 0; var continued = false
        val r = rig(); val repo = repo(r) { reads++; awaitCancellation() }
        val owner = launch { repo.observe() }; runCurrent()
        val action = launch { repo.reconcileAfterMutation(); continued = true }; runCurrent()
        val manual = launch { repo.refresh() }; runCurrent()
        assertEquals(1, reads); assertFalse(action.isCompleted)
        owner.cancel(); runCurrent()
        assertTrue(continued); assertFalse(action.isCancelled); assertTrue(manual.isCompleted)
        repo.reconcileAfterMutation(); assertEquals(1, reads)
        repo.close(); r.api.close()
    }

    @Test fun `actual mutation reconciliation caller cancellation propagates and leaves shared read owned`() = runTest {
        var reads = 0; var continued = false
        val fresh = CompletableDeferred<List<AdminSession>>()
        val r = rig(); val repo = repo(r) { if (++reads == 1) emptyList() else fresh.await() }
        val owner = launch { repo.observe() }; runCurrent()
        val action = launch { repo.reconcileAfterMutation(); continued = true }; runCurrent()
        action.cancel(); runCurrent(); fresh.complete(listOf(row)); runCurrent()
        assertTrue(action.isCancelled); assertFalse(continued); assertEquals(listOf(row), repo.sessions.value)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `mutation reconciliation terminal read clears data and releases active caller`() = runTest {
        var reads = 0; var continued = false
        val r = rig(); val repo = repo(r) { if (++reads == 1) listOf(row) else throw ApiError(403, "fixture") }
        val owner = launch { repo.observe() }; runCurrent()
        val action = launch { repo.reconcileAfterMutation(); continued = true }; runCurrent()
        assertTrue(continued); assertFalse(action.isCancelled)
        assertEquals(RealtimeState.SignInRequired, repo.state.value); assertNull(repo.sessions.value)
        repo.reconcileAfterMutation(); assertEquals(2, reads)
        owner.cancel(); repo.close(); r.api.close()
    }

    @Test fun `first connect is not Live until a read publishes rows after a fence discards the cue read`() = runTest {
        var reads = 0
        val first = CompletableDeferred<List<AdminSession>>(); val third = CompletableDeferred<List<AdminSession>>()
        val r = rig(); val repo = repo(r) { when (++reads) { 1 -> emptyList(); 2 -> first.await(); else -> third.await() } }
        val owner = launch { repo.observe() }; runCurrent()
        // read 1 (start) published rows; clear to model a first connect without rows.
        repo.invalidateAuthority(); runCurrent()
        assertNull(repo.sessions.value)
        r.up(); runCurrent()
        val mutation = launch { repo.reconcileAfterMutation() }; runCurrent()
        first.complete(listOf(row)); runCurrent()
        assertNull(repo.sessions.value); assertNotEquals(RealtimeState.Live, repo.state.value)
        third.complete(listOf(row)); runCurrent()
        assertEquals(listOf(row), repo.sessions.value); assertEquals(RealtimeState.Live, repo.state.value)
        assertTrue(mutation.isCompleted)
        owner.cancel(); repo.close(); r.api.close()
    }
}
