package com.bloemstudios.admin.streams

import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A held fallback GET captured the session before one successful administrative POST. */
class StreamsReconciliationTest {
    @Test fun `individual stop cannot use a pre mutation read to release its guard`() = checkBarrier(false)
    @Test fun `aggregate stop cannot use a pre mutation read to release its guard`() = checkBarrier(true)

    private fun checkBarrier(aggregate: Boolean) = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val freshRelease = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Unit>()
        val reads = AtomicInteger(); val posts = AtomicInteger()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = ApiClient("https://fixture.invalid", null, MockEngine { req ->
            when {
                req.url.encodedPath == "/api/v2/admin/sessions/s/stop" && req.method == HttpMethod.Post -> {
                    posts.incrementAndGet(); posted.complete(Unit); respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, json)
                }
                req.url.encodedPath == "/api/v2/admin/sessions" -> {
                    if (reads.incrementAndGet() == 1) {
                        oldStarted.complete(Unit); oldRelease.await()
                        respond(V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession("s", "u", "old", "movie", position = "0")), headers=json)
                    } else { freshStarted.complete(Unit); freshRelease.await(); respond("""{"items":[]}""", headers=json) }
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
        val single = StreamsViewModel(); val all = AggregateStreamsViewModel()
        val row = AdminSession("s", "user-1", "u", "profile-1", "old", "movie", "direct", 0.0, null, false)
        val server = AdminServer("a", "https://fixture.invalid", "Fixture")
        val pending = if (aggregate) all.pendingKeys else single.pendingKeys
        val key = if (aggregate) "a:s" else "s"
        suspend fun stop(api: ApiClient = client) = if (aggregate) all.stop(server, row) { api } else single.stop(row, api)
        val actionError = if (aggregate) all.actionError else single.actionError
        // Establish an observable failure with a separate no-owner fixture. The
        // successful target POST clears it immediately before entering reconciliation.
        // Both the action and this test resume on runBlocking's event loop, so observing
        // that clear is a deterministic barrier (no sleep or private repository access).
        val denied = ApiClient("https://denied.invalid", null, MockEngine {
            respond("{}", HttpStatusCode.BadRequest, json)
        })
        try { assertEquals(ActionResult.Failure, stop(denied)); assertNotNull(actionError.value) }
        finally { denied.close() }
        val owner = launch { client.liveSessions.observe() }
        try {
            withTimeout(5_000) { oldStarted.await() }
            val action = async { stop() }
            withTimeout(5_000) { posted.await(); actionError.first { it == null } }
            assertEquals(setOf(key), pending.value)
            oldRelease.complete(Unit)
            withTimeout(5_000) {
                select<Unit> {
                    freshStarted.onAwait { }
                    action.onAwait { error("Action completed by accepting the pre-mutation GET") }
                }
            }
            assertFalse(action.isCompleted)
            assertEquals(setOf(key), pending.value)
            assertEquals(ActionResult.Busy, stop())
            assertEquals(1, posts.get())
            freshRelease.complete(Unit)
            assertEquals(ActionResult.Success, withTimeout(5_000) { action.await() })
            assertEquals(emptyList<AdminSession>(), client.liveSessions.sessions.value)
            assertTrue(pending.value.isEmpty()); assertEquals(2, reads.get()); assertEquals(1, posts.get())
        } finally { oldRelease.complete(Unit); freshRelease.complete(Unit); owner.cancelAndJoin(); client.close() }
    }
}
