package com.bloemstudios.admin.libraries

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateLibrariesViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A server with one library named [libraryName]; every request it gets is recorded in [requests]. */
    private class Recorded(val requests: MutableList<HttpRequestData> = java.util.concurrent.CopyOnWriteArrayList())

    private fun client(libraryName: String, recorded: Recorded, down: Boolean = false, activeRuns: String = """{"items":[]}"""): ApiClient =
        ApiClient("https://x.test", "tok", MockEngine { request ->
            recorded.requests += request
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                down -> respond("""{"detail":"unavailable"}""", HttpStatusCode.ServiceUnavailable, json)
                request.url.encodedPath == "/api/v2/libraries" ->
                    respond("""{"items":[{"id":"1","name":"$libraryName","type":"movies","paths":["/m"],"enabled":true}]}""", HttpStatusCode.OK, json)
                request.url.encodedPath == "/api/v2/scan/capabilities" -> respond("""{"state":"available"}""", HttpStatusCode.OK, json)
                request.url.encodedPath == "/api/v2/scan" -> respond("""{"status":"accepted","mode":"library","library_id":"1"}""", HttpStatusCode.Accepted, json)
                request.url.encodedPath == "/api/v2/admin/autoscan/scans" ->
                    respond(if (request.url.parameters["status"] == "running") activeRuns else """{"items":[]}""", HttpStatusCode.OK, json)
                else -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
            }
        })

    @Test
    fun `each server reads into its own page`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val a = Recorded(); val b = Recorded()
        val clients = mapOf("srv-a" to client("Movies A", a), "srv-b" to client("Movies B", b))

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        assertEquals(listOf("Movies A"), viewModel.pageFor(serverA).state.value.libraries.map { it.name })
        assertEquals(listOf("Movies B"), viewModel.pageFor(serverB).state.value.libraries.map { it.name })
        assertTrue(a.requests.isNotEmpty() && b.requests.isNotEmpty())
    }

    @Test
    fun `a server that is down marks only its own section`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val clients = mapOf("srv-a" to client("Movies A", Recorded()), "srv-b" to client("Movies B", Recorded(), down = true))

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        assertTrue(viewModel.pageFor(serverA).state.value.loaded)
        assertNull(viewModel.pageFor(serverA).state.value.loadFailure)
        assertFalse(viewModel.pageFor(serverB).state.value.loaded)
        assertNotNull(viewModel.pageFor(serverB).state.value.loadFailure)
    }

    @Test
    fun `a server with no usable client reports that for its own section`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val a = Recorded()

        viewModel.load(listOf(serverA, serverB)) { if (it.id == "srv-a") client("Movies A", a) else error("no token for ${it.id}") }

        assertTrue(viewModel.pageFor(serverA).state.value.loaded)
        assertNotNull(viewModel.pageFor(serverB).state.value.loadFailure)
    }

    @Test
    fun `a scan started in one section is sent to that server only`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val a = Recorded(); val b = Recorded()
        val clients = mapOf("srv-a" to client("Movies A", a), "srv-b" to client("Movies B", b))
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }
        a.requests.clear(); b.requests.clear()

        val pageB = viewModel.pageFor(serverB)
        pageB.scan(clients.getValue("srv-b"), pageB.state.value.libraries.single())

        assertTrue(a.requests.isEmpty())
        assertEquals(1, b.requests.count { it.method == HttpMethod.Post && it.url.encodedPath == "/api/v2/scan" })
        assertEquals(ScanState.Queued, pageB.scanStatus("1")?.state)
        assertNull("the other server's library with the same id shows nothing", viewModel.pageFor(serverA).scanStatus("1"))
    }

    @Test
    fun `an error in a section names its server`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val failing = ApiClient("https://x.test", "tok", MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/libraries" -> respond("""{"items":[{"id":"1","name":"M","type":"movies","paths":[],"enabled":true}]}""", HttpStatusCode.OK, json)
                "/api/v2/scan/capabilities" -> respond("""{"state":"available"}""", HttpStatusCode.OK, json)
                "/api/v2/scan" -> respond("""{"detail":"Library is disabled"}""", HttpStatusCode.Conflict, json)
                else -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
            }
        })
        viewModel.load(listOf(serverB)) { failing }
        val page = viewModel.pageFor(serverB)

        page.scan(failing, page.state.value.libraries.single())

        assertEquals(
            com.bloemstudios.admin.network.ErrorText.Prefixed("Server B", com.bloemstudios.admin.network.ErrorText.Raw("Library is disabled")),
            page.state.value.error,
        )
    }

    @Test
    fun `the same page is returned for a server every time and a different one for another server`() {
        val viewModel = AggregateLibrariesViewModel()
        assertSame(viewModel.pageFor(serverA), viewModel.pageFor(serverA))
        assertFalse(viewModel.pageFor(serverA) === viewModel.pageFor(serverB))
    }

    @Test
    fun `any server with a scan under way asks for the faster read`() = runTest {
        val viewModel = AggregateLibrariesViewModel()
        val clients = mapOf(
            "srv-a" to client("Movies A", Recorded()),
            "srv-b" to client("Movies B", Recorded(), activeRuns = """{"items":[{"id":"r","library_id":"1","status":"running"}]}"""),
        )
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        assertTrue(viewModel.anyActive(listOf(serverA, serverB)))
        assertFalse(viewModel.anyActive(listOf(serverA)))
    }
}
