package com.bloemstudios.admin.servers

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.InvalidServerOrigin
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ServerFanOutTest {
    @Serializable
    data class Probe(val value: String)

    private fun serverClient(id: String, engine: MockEngine): ApiClient =
        ApiClient("https://$id.test", "tok-$id", engine)

    @Test
    fun `legacy cleartext origin does not invoke a client factory during fan-out`() = runTest {
        val legacyServer = AdminServer("legacy", "http://legacy.test", "Legacy")
        var clientsCreated = 0

        val results = fanOutToServers(
            servers = listOf(legacyServer),
            clientFor = {
                clientsCreated++
                error("legacy origin must not create a client")
            },
            request = { error("legacy origin must not make a request") }
        )

        assertEquals(0, clientsCreated)
        assertTrue(results.single().result.exceptionOrNull() is InvalidServerOrigin)
    }

    @Test
    fun `fans out to every server and maps results back to their own server`() = runTest {
        val servers = listOf(
            AdminServer("srv-1", "https://srv-1.test", "Server One"),
            AdminServer("srv-2", "https://srv-2.test", "Server Two")
        )
        val clients = mapOf(
            "srv-1" to serverClient("srv-1", MockEngine { respond("""{"value":"one"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "srv-2" to serverClient("srv-2", MockEngine { respond("""{"value":"two"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        )

        val results = fanOutToServers(servers, clientFor = { clients.getValue(it.id) }) { client -> client.get<Probe>("/probe") }

        assertEquals(2, results.size)
        val bySrv = results.associateBy { it.server.id }
        assertEquals("one", bySrv.getValue("srv-1").result.getOrThrow().value)
        assertEquals("two", bySrv.getValue("srv-2").result.getOrThrow().value)
    }

    @Test
    fun `a mix of success and failure servers produces per-server Results without aborting the whole fan-out`() = runTest {
        val servers = listOf(
            AdminServer("ok", "https://ok.test", "OK Server"),
            AdminServer("bad", "https://bad.test", "Expired Server"),
            AdminServer("unreachable", "https://unreachable.test", "Unreachable Server")
        )
        val clients = mapOf(
            "ok" to serverClient("ok", MockEngine { respond("""{"value":"fine"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "bad" to serverClient("bad", MockEngine { respond("""{"message":"expired"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "unreachable" to serverClient("unreachable", MockEngine { throw java.io.IOException("network down") })
        )

        val results = fanOutToServers(servers, clientFor = { clients.getValue(it.id) }) { client -> client.get<Probe>("/probe") }

        val bySrv = results.associateBy { it.server.id }
        assertTrue(bySrv.getValue("ok").result.isSuccess)
        assertEquals("fine", bySrv.getValue("ok").result.getOrThrow().value)
        assertTrue(bySrv.getValue("bad").result.isFailure)
        assertTrue(bySrv.getValue("unreachable").result.isFailure)
    }

    @Test
    fun `requests run concurrently rather than sequentially`() = runTest {
        val concurrentCount = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val servers = (1..5).map { AdminServer("srv-$it", "https://srv-$it.test", "Server $it") }

        val results = fanOutToServers(servers, clientFor = { _ -> ApiClient("https://x.test", "tok") }) { _ ->
            val current = concurrentCount.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, current) }
            delay(50)
            concurrentCount.decrementAndGet()
            "done"
        }

        assertEquals(5, results.size)
        // With sequential execution maxObservedConcurrency would be 1 (each
        // delay(50) would fully complete before the next request started).
        // Running concurrently, multiple requests are in-flight at once.
        assertTrue("expected concurrent execution, saw max concurrency of ${maxObservedConcurrency.get()}", maxObservedConcurrency.get() > 1)
    }

    @Test(expected = CancellationException::class)
    fun `a cancelled request propagates as cancellation instead of becoming a failed-section Result`() = runTest {
        val servers = listOf(AdminServer("srv-1", "https://srv-1.test", "Server One"))

        // runCatching (used internally) would otherwise swallow this as an
        // ordinary Throwable and report it back as Result.failure -- which
        // would render as a misleading "sign-in needed"/error row for a
        // request that was never actually rejected by any server at all.
        fanOutToServers(servers, clientFor = { _ -> ApiClient("https://x.test", "tok") }) { _ ->
            throw CancellationException("request cancelled")
        }
    }
    @Test
    fun `fanout bounds a whole section request and keeps healthy results`() = runTest {
        val good = AdminServer("good", "https://good.test", "Good")
        val slow = AdminServer("slow", "https://slow.test", "Slow")
        val clients = listOf(good, slow).associate { it.id to ApiClient(it.baseUrl, null) }
        try {
            val results = fanOutToServers(listOf(good, slow), clientFor = { clients.getValue(it.id) }, sectionTimeoutMillis = 50) { client ->
                if (client === clients["slow"]) kotlinx.coroutines.delay(60_000)
                "loaded"
            }
            assertTrue(results.first { it.server.id == "good" }.result.isSuccess)
            assertTrue(results.first { it.server.id == "slow" }.result.isFailure)
        } finally { clients.values.forEach { it.close() } }
    }

}
