package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.dashboard.DashboardViewModel
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.requests.CountsTarget
import com.bloemstudios.admin.requests.RequestCountsPoller
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/**
 * The count of timed requests with the app open on the dashboard and a healthy socket, five minutes. The same harness
 * measured the previous code at 75 requests (15 a minute: the dashboard's seven reads every 30 s and the request badge
 * every 60 s).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingCountTest {
    @Test fun `after - no timed requests on the dashboard while the socket is connected`() = runTest {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/v2/admin/stats" -> V2Fixtures.ADMIN_STATS
                "/api/v2/admin/system/resources" -> V2Fixtures.SYSTEM_RESOURCES
                "/api/v2/admin/requests/counts" -> """{"needs_approval":2}"""
                else -> """{"items":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val transport = FakeLiveTransport { testScheduler.currentTime }
        val live = LiveConnection(client, transport, backgroundScope, FakeEnvironment())
        val repo = LiveSessionsRepository(client, live, parentScope = backgroundScope)
        val model = DashboardViewModel()
        // The dashboard as it is now: the sessions follow the socket, the page reads once on open, the badge reads once on open.
        val sessions = launch { repo.observe() }
        val screen = launch { model.load(client) }
        val badge = launch { RequestCountsPoller().run(listOf(CountsTarget("one") { client })) }
        // The mock engine answers on a real I/O thread, so give each virtual second a moment of real time to land.
        fun settle() { repeat(4) { Thread.sleep(8); runCurrent() } }
        settle()
        val opened = paths.size
        val connected = live.status.value
        repeat(5 * 60) { advanceTimeBy(1_000); settle() }
        val timed = paths.size - opened
        println("AFTER opened=$opened timed_in_5_min=$timed per_minute=${timed / 5.0} socket=$connected")
        println("AFTER by path: " + paths.groupingBy { it }.eachCount())
        assertEquals(LiveStatus.Live, connected)
        assertEquals("no request is made on a timer while connected", 0, timed)
        assertEquals(1, transport.attempts)
        sessions.cancel(); screen.cancel(); badge.cancel(); repo.close(); client.close()
    }
}
