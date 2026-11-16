package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.realtime.RealtimeState
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AggregateDashboardViewModelTest {
    private val a = AdminServer("a", "https://a.test", "Server A")
    private val b = AdminServer("b", "https://b.test", "Server B")
    private val stats = V2Fixtures.ADMIN_STATS
    private val host = V2Fixtures.SYSTEM_RESOURCES

    @Test fun `deferred host on A cannot delay B sessions health or catalog`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fun client(server: AdminServer) = ApiClient(server.baseUrl, null, MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/resources") -> {
                    if (server.id == a.id) { entered.complete(Unit); release.await() }; host
                }
                request.url.encodedPath.endsWith("/admin/stats") -> stats
                request.url.encodedPath.endsWith("/admin/sessions") -> """{"items":[]}"""
                else -> "[]"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val clients = mapOf(a.id to client(a), b.id to client(b)); val model = AggregateDashboardViewModel()
        val owner = backgroundScope.launch { clients.getValue(b.id).liveSessions.observe() }
        val loading = launch { model.load(listOf(a,b)) { clients.getValue(it.id) } }
        try {
            entered.await()
            withContext(Dispatchers.Default) { withTimeout(5_000) {
                model.samples.value.getValue(b.id).hostStats.first { it != null }
                model.samples.value.getValue(b.id).stats.first { it != null }
                clients.getValue(b.id).liveSessions.sessions.first { it != null }
            } }
            assertFalse(loading.isCompleted)
            assertNull(model.samples.value.getValue(a.id).hostStats.value)
            assertNotNull(model.samples.value.getValue(b.id).sampledAt.value)
        } finally { release.complete(Unit); loading.join(); owner.cancel(); clients.values.forEach { it.close() } }
    }

    @Test fun `partial access failure does not erase another server health`() = runTest {
        val bad = ApiClient(a.baseUrl, null, MockEngine { respond("{}", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json")) })
        val good = ApiClient(b.baseUrl, null, MockEngine { request -> respond(if (request.url.encodedPath.endsWith("resources")) host else stats, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val owner = backgroundScope.launch { bad.liveSessions.observe() }
        val model = AggregateDashboardViewModel()
        try {
            model.load(listOf(a,b)) { if (it.id == a.id) bad else good }
            withContext(Dispatchers.Default) { withTimeout(5_000) { bad.liveSessions.state.first { it == RealtimeState.SignInRequired } } }
            assertNull(bad.liveSessions.sessions.value)
            assertNotNull(model.samples.value.getValue(b.id).hostStats.value)
        } finally { owner.cancel(); bad.close(); good.close() }
    }

    @Test fun `added removed and empty servers reconcile without retaining removed samples`() = runTest {
        val model = AggregateDashboardViewModel()
        model.reconcile(emptyList()); assertTrue(model.samples.value.isEmpty())
        model.reconcile(listOf(a)); val original = model.samples.value.getValue(a.id)
        model.reconcile(listOf(a,b)); assertSame(original, model.samples.value.getValue(a.id))
        model.reconcile(listOf(b)); assertEquals(setOf(b.id), model.samples.value.keys)
        model.reconcile(emptyList()); assertTrue(model.samples.value.isEmpty())
    }

    @Test fun `principal replacement synchronously clears sampled data and rejects late response`() = runTest {
        val pending = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var delayed = false
        val client = ApiClient(a.baseUrl, null, MockEngine { request ->
            if (delayed) { entered.complete(Unit); withContext(NonCancellable) { pending.await() } }
            respond(if (request.url.encodedPath.endsWith("resources")) host else stats, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val model = DashboardViewModel()
        try {
            model.load(client); assertNotNull(model.stats.value)
            delayed = true
            val loading = launch { model.load(client) }; entered.await()
            client.replaceAuthenticatedPrincipal("new-fixture")
            assertNull(model.stats.value); assertNull(model.hostStats.value)
            pending.complete(Unit); loading.join()
            assertNull(model.stats.value); assertNull(model.hostStats.value)
        } finally { pending.complete(Unit); client.close() }
    }
}
