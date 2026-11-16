package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import com.bloemstudios.admin.realtime.FakeEnvironment
import com.bloemstudios.admin.realtime.FakeLiveTransport
import com.bloemstudios.admin.realtime.LiveConfig
import com.bloemstudios.admin.realtime.LiveConnection
import com.bloemstudios.admin.realtime.LiveStatus
import com.bloemstudios.admin.realtime.OrganizationLiveChannels
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.realtime.testApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The before-and-after count of timed requests while the app sits open on the organization's Now playing page
 * with the real [OrganizationStreamsViewModel] reading through a pass client.
 *
 * Before: the loop the page ran until the shared events socket replaced the timers (`while: load; sleep 15 s`), replayed
 * with the same view model. Since that change the page has read on open and on pull only: it does not follow the
 * organization at all. After: the page follows `org.streams`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationStreamsRequestRateTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val streamsPath = "/api/bloem/v2/admin/organization/streams"
    private val paths = Collections.synchronizedList(mutableListOf<String>())

    private suspend fun opened(): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine { respond(F.session(token = "pass"), HttpStatusCode.OK, json) })
        return OrganizationSession(
            account, F.ORG_ID, clock = { Instant.parse("2026-11-13T12:00:00Z") },
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    paths += request.url.encodedPath
                    val body = when (request.url.encodedPath) {
                        "/api/bloem/v2/admin/organization/support-capabilities" -> F.supportCapabilities()
                        streamsPath -> F.supportStreams(F.supportStream("st1"))
                        else -> F.error("not_found")
                    }
                    respond(body, if (request.url.encodedPath == streamsPath || request.url.encodedPath.endsWith("support-capabilities")) HttpStatusCode.OK else HttpStatusCode.NotFound, json)
                }, recovery)
            },
        ).also { it.open() }
    }

    private fun TestScope.connection(script: (suspend (com.bloemstudios.admin.realtime.LiveControl, Int) -> Unit)? = null): Pair<LiveConnection, FakeLiveTransport> {
        val transport = FakeLiveTransport { testScheduler.currentTime }
        script?.let { transport.script = it }
        return LiveConnection(testApi(), transport, backgroundScope, FakeEnvironment(), LiveConfig(), { 1.0 }) to transport
    }

    private val reads get() = synchronized(paths) { paths.count { it == streamsPath } }

    /** The mock engine answers on a real I/O thread, so each virtual step gets a moment of real time to land. */
    private fun TestScope.settle() { repeat(4) { Thread.sleep(8); runCurrent() } }

    private fun TestScope.minutes(count: Int) = repeat(count * 12) { advanceTimeBy(5_000); settle() }

    @Test fun `the count of timed requests a minute on the organization Now playing page`() = runTest {
        val model = OrganizationStreamsViewModel()
        val minutes = 5

        // Before: the 15-second loop the page ran, replayed.
        val old = opened()
        val loop = launch { while (isActive) { model.load(old); delay(15_000) } }
        settle()
        val oldOpen = reads
        minutes(minutes)
        loop.cancel()
        val before = (reads - oldOpen) / minutes.toDouble()

        // The page as it stands on main: read on open, nothing else.
        paths.clear()
        val onMain = opened()
        model.load(onMain)
        val mainOpen = reads
        minutes(minutes)
        val main = (reads - mainOpen) / minutes.toDouble()

        // After, socket live: the open and the connection's own snapshot, then only what the socket says.
        paths.clear()
        val session = opened()
        val (live, transport) = connection()
        val page = launch { watchOrganization(true, { live }, setOf(OrganizationLiveChannels.STREAMS)) { model.load(session) } }
        settle(); advanceTimeBy(3_000); settle()
        val opening = reads
        assertEquals(LiveStatus.Live, live.status.value)
        minutes(minutes)
        val after = (reads - opening) / minutes.toDouble()
        // A stream starts: one more read, at once.
        transport.controls.last().onEvent(OrganizationLiveChannels.STREAMS); advanceTimeBy(300); settle()
        val reacted = reads - opening
        page.cancel()

        // After, a server that has not got the socket: exactly what main does.
        paths.clear()
        val older = opened()
        val offeredNothing = launch { watchOrganization(false, { error("no socket on this server") }, setOf(OrganizationLiveChannels.STREAMS)) { model.load(older) } }
        settle()
        val olderOpen = reads
        minutes(minutes)
        val without = (reads - olderOpen) / minutes.toDouble()
        offeredNothing.cancel()

        // After, the socket cannot be had: the visible page reads once a minute, which is the fallback of the client rules.
        paths.clear()
        val down = opened()
        val (deadLive, _) = connection { _, _ -> FakeLiveTransport.fail(SessionFailure.Connection) }
        val offline = launch { watchOrganization(true, { deadLive }, setOf(OrganizationLiveChannels.STREAMS)) { model.load(down) } }
        settle()
        val downOpen = reads
        minutes(minutes)
        val whileDown = (reads - downOpen) / minutes.toDouble()
        offline.cancel()

        println("ORG-STREAMS timed requests per minute, page open $minutes minutes:")
        println("  before (15 s loop)            $before")
        println("  main (open and pull only)     $main")
        println("  after, socket live            $after   (one more read per change: $reacted)")
        println("  after, server without socket  $without")
        println("  after, socket down            $whileDown")
        assertEquals("the loop the socket replaced", 4.0, before, 0.21)
        assertEquals(0.0, main, 0.0)
        assertEquals("no request is made on a timer while connected", 0.0, after, 0.0)
        assertEquals("a change is read once", 1, reacted)
        assertEquals("a server without the socket keeps exactly what the page did", 0.0, without, 0.0)
        assertTrue("socket down: at most once a minute, was $whileDown", whileDown <= 1.0)
        assertEquals(1, transport.attempts)
    }
}
