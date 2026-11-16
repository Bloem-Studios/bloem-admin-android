package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Needs approval count on the Requests menu item: read every 60 seconds, summed over servers, stopped when the server cannot answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestCountsPollerTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fake(var needs: Int = 3, var status: HttpStatusCode = HttpStatusCode.OK, var slug: String = "dependency_unavailable") {
        var reads = 0
    }

    private fun Fake.target(key: String = "a") = CountsTarget(key) {
        ApiClient("https://example.test", null, MockEngine { _ ->
            reads++
            if (status == HttpStatusCode.OK) respond("""{"needs_approval":$needs,"in_progress":1,"failed":0,"done":9}""", HttpStatusCode.OK, json)
            else respond(V2Fixtures.problem(status.value, slug, "Down"), status, json)
        })
    }

    /** Runs the poller until its pause throws [StopPolling]. */
    private suspend fun RequestCountsPoller.runFor(@Suppress("UNUSED_PARAMETER") rounds: Int, targets: List<CountsTarget>) {
        try {
            run(targets)
        } catch (_: StopPolling) {
        }
    }

    private class StopPolling : CancellationException("test over")

    private fun poller(rounds: Int, waits: MutableList<Long>, between: (Int) -> Unit = {}) =
        RequestCountsPoller(pause = {
            waits += 0L
            between(waits.size)
            if (waits.size >= rounds) throw StopPolling()
        })

    @Test
    fun `reads at once, then only when nudged`() = runTest {
        val server = Fake(needs = 3)
        val waits = mutableListOf<Long>()
        val poller = poller(rounds = 2, waits = waits) { if (it == 1) server.needs = 5 }

        poller.runFor(2, listOf(server.target()))

        assertEquals(2, waits.size)
        assertEquals("one read before the first wait, one after", 2, server.reads)
        assertEquals(5, poller.needsApproval.value)
    }

    @Test
    fun `sums the servers that answered`() = runTest {
        val a = Fake(needs = 2)
        val b = Fake(needs = 4)
        val poller = poller(rounds = 1, waits = mutableListOf())

        poller.runFor(1, listOf(a.target("a"), b.target("b")))

        assertEquals(6, poller.needsApproval.value)
    }

    @Test
    fun `stops reading a server that says request administration is unavailable`() = runTest {
        val gone = Fake(status = HttpStatusCode.ServiceUnavailable)
        val fine = Fake(needs = 2)
        val poller = poller(rounds = 3, waits = mutableListOf())

        poller.runFor(3, listOf(gone.target("a"), fine.target("b")))

        assertEquals("asked once, then left alone", 1, gone.reads)
        assertEquals(3, fine.reads)
        assertEquals(2, poller.needsApproval.value)
    }

    @Test
    fun `returns once no server is left to read`() = runTest {
        val gone = Fake(status = HttpStatusCode.ServiceUnavailable)
        val waits = mutableListOf<Long>()
        val poller = poller(rounds = 5, waits = waits)

        poller.run(listOf(gone.target()))

        assertTrue("it never waited for another poll", waits.isEmpty())
        assertEquals(1, gone.reads)
        assertNull(poller.needsApproval.value)
    }

    @Test
    fun `a rights problem also stops it`() = runTest {
        val denied = Fake(status = HttpStatusCode.Forbidden, slug = "permission_denied")
        val waits = mutableListOf<Long>()

        poller(rounds = 5, waits = waits).run(listOf(denied.target()))

        assertTrue(waits.isEmpty())
        assertEquals(1, denied.reads)
    }

    @Test
    fun `a passing failure keeps the last count and tries again`() = runTest {
        val server = Fake(needs = 3)
        val seen = mutableListOf<Int?>()
        lateinit var poller: RequestCountsPoller
        poller = RequestCountsPoller(pause = {
            seen += poller.needsApproval.value
            when (seen.size) {
                1 -> { server.status = HttpStatusCode.InternalServerError; server.slug = "internal_error" }
                2 -> { server.status = HttpStatusCode.OK; server.needs = 1 }
                else -> throw StopPolling()
            }
        })

        poller.runFor(3, listOf(server.target()))

        assertEquals("the badge does not blink off on a weak connection", listOf(3, 3, 1), seen)
        assertEquals(3, server.reads)
    }

    @Test
    fun `no count is shown before the first answer`() {
        assertNull(RequestCountsPoller().needsApproval.value)
    }

    @Test
    fun `without a nudge nothing is read again, however long it waits`() = runTest {
        val server = Fake(needs = 3)
        val poller = RequestCountsPoller()
        // The mock engine answers on an I/O thread: give each step a moment of real time to land.
        fun settle() { repeat(5) { Thread.sleep(10); runCurrent() } }
        val job = launch { poller.run(listOf(server.target())) }
        settle()
        assertEquals(1, server.reads)
        advanceTimeBy(60 * 60_000L); settle()
        assertEquals("no timer reads the count", 1, server.reads)
        server.needs = 4
        poller.nudge(); settle()
        assertEquals(2, server.reads); assertEquals(4, poller.needsApproval.value)
        job.cancel()
    }
}
