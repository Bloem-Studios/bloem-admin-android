package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass
import java.security.Provider
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KtorLiveTransportTest {
    companion object {
        private var androidProvider: Provider? = null
        private var androidProviderPosition = 0

        @BeforeClass @JvmStatic fun useJvmFixtureProviders() {
            // Robolectric leaves Conscrypt in the JVM provider list. Its Java 21 server
            // reflection and binary-EC AlgorithmParameters break JSSE fixture setup.
            // This plain JVM class uses JDK TLS providers; restore Android's provider
            // at its exact former priority after the class, including failed tests.
            val providers = Security.getProviders()
            val index = providers.indexOfFirst { it.name == "Conscrypt" }
            if (index >= 0) {
                androidProvider = providers[index]
                androidProviderPosition = index + 1
                Security.removeProvider("Conscrypt")
            }
        }

        @AfterClass @JvmStatic fun restoreAndroidProvider() {
            androidProvider?.let { Security.insertProviderAt(it, androidProviderPosition) }
            androidProvider = null
        }
    }

    @Test fun `TLS upgrade offers ticket protocols without bearer or URL credentials and signals snapshot and replacements`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            val ticketRequest = checkNotNull(server.requests.poll())
            val upgrade = checkNotNull(server.requests.poll())
            assertTrue(ticketRequest.line == "POST /api/v2/events/ws-ticket HTTP/1.1")
            assertTrue(ticketRequest.headers["authorization"] == "Bearer fixture-access")
            assertTrue(upgrade.line == "GET /api/v2/events/ws HTTP/1.1")
            assertNull(upgrade.headers["authorization"])
            assertTrue(upgrade.headers["sec-websocket-protocol"] == "silo.events.v2, silo.ticket.${server.minted.single()}")
            assertNull(withContext(Dispatchers.IO) { connection.messages.poll(150, TimeUnit.MILLISECONDS) })
            connection.text(EventsProtocolTest.HELLO)
            assertEquals("""{"type":"subscribe","request_id":"live-1","channels":["sessions"]}""", withContext(Dispatchers.IO) { connection.messages.poll(3, TimeUnit.SECONDS) })
            connection.text(EventsProtocolTest.ACK)
            assertTrue(attempt.sessions.tryReceive().isFailure)
            connection.text(EventsProtocolTest.snapshot(EventsProtocolTest.ROW))
            withTimeout(3000) { attempt.sessions.receive() }  // snapshot cue; its rows are never decoded
            connection.text(EventsProtocolTest.REPLACEMENT)
            withTimeout(3000) { attempt.sessions.receive() }
            assertTrue(attempt.sessions.tryReceive().isFailure)
            attempt.job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(3, TimeUnit.SECONDS) })
            assertTrue(attempt.result.await() is CancellationException)
        }
    }

    @Test fun `wrong or missing selected protocol never subscribes`() = runBlocking {
        for (protocol in listOf("wrong", null)) fixture { server, api ->
            server.selectedProtocol = protocol
            val attempt = attempt(KtorLiveTransport(api))
            assertFailure(SessionFailure.Protocol, attempt.result.await())
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    @Test fun `malformed ticket shape or protocol never opens socket`() = runBlocking {
        for (badShape in listOf(true, false)) fixture { server, api ->
            server.invalidTicket = badShape
            if (!badShape) server.ticketProtocol = "wrong"
            val attempt = attempt(KtorLiveTransport(api))
            assertFailure(SessionFailure.Protocol, attempt.result.await())
            assertTrue(server.connections.isEmpty())
        }
    }

    @Test fun `ticket and upgrade authorization failures are terminal while missing endpoints are unsupported`() = runBlocking {
        for (upgrade in listOf(false, true)) for ((status, expected) in listOf(401 to SessionFailure.SignInRequired, 403 to SessionFailure.SignInRequired, 404 to SessionFailure.Unsupported, 503 to SessionFailure.Connection)) {
            fixture { server, api ->
                if (upgrade) server.upgradeStatus = status else server.ticketStatus = status
                val attempt = attempt(KtorLiveTransport(api))
                assertFailure(expected, attempt.result.await())
            }
        }
    }

    @Test fun `access changed frame and close 4001 end the attempt as access changed without another snapshot`() = runBlocking {
        for (textSignal in listOf(true, false)) fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.ready()
            attempt.sessions.receive()
            if (textSignal) connection.text("""{"type":"access_changed"}""") else connection.close(4001)
            assertFailure(SessionFailure.AccessChanged, withTimeout(3000) { attempt.result.await() })
        }
    }

    @Test fun `snapshot deadline includes silent HTTP upgrade and closes late opened connection`() = runBlocking {
        fixture { server, api ->
            server.pauseUpgrade = CountDownLatch(1)
            val attempt = attempt(KtorLiveTransport(api, initialTimeoutMillis = 1000))
            withContext(Dispatchers.IO) { repeat(2) { checkNotNull(server.requests.poll(3, TimeUnit.SECONDS)) } }
            assertFailure(SessionFailure.Deadline, withTimeout(3000) { attempt.result.await() })
            server.pauseUpgrade!!.countDown()
            // A late 101 must not create a surviving client or publish data.
            val late = withContext(Dispatchers.IO) { server.connections.poll(1, TimeUnit.SECONDS) }
            if (late != null) assertTrue(withContext(Dispatchers.IO) { late.closed.await(2, TimeUnit.SECONDS) })
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    @Test fun `ack without snapshot expires, and an error frame does not end the connection`() = runBlocking {
        for (errorFrame in listOf(false, true)) fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api, initialTimeoutMillis = 1200))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO); connection.text(EventsProtocolTest.ACK)
            if (errorFrame) connection.text("""{"type":"error","code":"internal_error","message":"private diagnostic"}""")
            assertFailure(SessionFailure.Deadline, withTimeout(3000) { attempt.result.await() })
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    @Test fun `snapshot before acknowledgement and replacement before snapshot fail`() = runBlocking {
        for (frame in listOf(EventsProtocolTest.snapshot(), EventsProtocolTest.REPLACEMENT)) fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO)
            connection.text(frame)
            assertFailure(SessionFailure.Protocol, withTimeout(3000) { attempt.result.await() })
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    @Test fun `principal replacement during mint prevents upgrade`() = runBlocking {
        fixture { server, api ->
            server.onTicket = { api.replaceAuthenticatedPrincipal("replacement-access") }
            val attempt = attempt(KtorLiveTransport(api))
            assertFailure(SessionFailure.SignInRequired, withTimeout(3000) { attempt.result.await() })
            assertTrue(server.connections.isEmpty())
        }
    }

    @Test fun `principal replacement closes idle stream immediately`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            api.replaceAuthenticatedPrincipal("replacement-access")
            assertFailure(SessionFailure.SignInRequired, withTimeout(3000) { attempt.result.await() })
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(3, TimeUnit.SECONDS) })
        }
    }

    @Test fun `refresh keeps authority and next attempt mints new ticket using rotated bearer`() = runBlocking {
        fixture { server, api ->
            val transport = KtorLiveTransport(api)
            val first = attempt(transport)
            val one = server.connection(); one.ready(); first.sessions.receive()
            api.refreshAccessToken(AccessTokenSnapshot("rotated-access", 1))
            one.text(EventsProtocolTest.REPLACEMENT); first.sessions.receive()
            one.close(1000)
            assertFailure(SessionFailure.Connection, withTimeout(3000) { first.result.await() })
            val second = attempt(transport)
            val two = server.connection(); two.ready(); second.sessions.receive()
            assertEquals(2, server.minted.size)
            assertTrue(server.minted[0] != server.minted[1])
            val requests = server.requests.toList()
            assertTrue(requests[2].headers["authorization"] == "Bearer rotated-access")
            assertTrue(requests[3].headers["sec-websocket-protocol"]!!.endsWith(server.minted[1]))
            second.job.cancelAndJoin()
        }
    }

    @Test fun `replacement collection cancels previous connection before new publication`() = runBlocking {
        fixture { server, api ->
            val transport = KtorLiveTransport(api)
            val first = attempt(transport)
            val one = server.connection(); one.ready(); first.sessions.receive()
            val second = attempt(transport)
            val two = server.connection()
            assertTrue(first.result.await() is CancellationException)
            assertTrue(withContext(Dispatchers.IO) { one.closed.await(3, TimeUnit.SECONDS) })
            two.ready(); second.sessions.receive()
            assertTrue(first.sessions.tryReceive().isFailure)
            second.job.cancelAndJoin()
        }
    }

    @Test fun `cancellation while upgrade is silent returns promptly and never leaks late session`() = runBlocking {
        fixture { server, api ->
            server.pauseUpgrade = CountDownLatch(1)
            val attempt = attempt(KtorLiveTransport(api))
            withContext(Dispatchers.IO) { repeat(2) { checkNotNull(server.requests.poll(3, TimeUnit.SECONDS)) } }
            withTimeout(1000) { attempt.job.cancelAndJoin() }
            server.pauseUpgrade!!.countDown()
            val late = withContext(Dispatchers.IO) { server.connections.poll(1, TimeUnit.SECONDS) }
            if (late != null) assertTrue(withContext(Dispatchers.IO) { late.closed.await(2, TimeUnit.SECONDS) })
        }
    }

    @Test fun `live stream survives REST idle timeout and responds to server ping`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api, initialTimeoutMillis = 2000))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            delay(21_000)
            assertFalse(attempt.result.isCompleted)
            connection.ping()
            assertTrue(withContext(Dispatchers.IO) { connection.pong.await(3, TimeUnit.SECONDS) })
            connection.text(EventsProtocolTest.REPLACEMENT)
            withTimeout(3000) { attempt.sessions.receive() }
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `oversized frames fail without publishing`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            runCatching { connection.text("""{"type":"future","padding":"${"x".repeat(1_048_577)}"}""") }
            assertTrue(withTimeout(3000) { attempt.result.await() } is SessionTransportException)
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    @Test fun `upgrade redirect cannot forward ticket to another origin`() = runBlocking {
        TlsEventsFixture().use { other ->
            fixture { server, api ->
                server.upgradeStatus = 302
                server.redirectLocation = other.origin + "/redirect-target"
                val attempt = attempt(KtorLiveTransport(api))
                assertFailure(SessionFailure.Connection, withTimeout(3000) { attempt.result.await() })
                assertEquals(2, server.requests.size)
                assertTrue(other.requests.isEmpty())
            }
        }
    }

    @Test fun `API close terminates a live collection`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            api.close()
            assertFailure(SessionFailure.Connection, withTimeout(3000) { attempt.result.await() })
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(3, TimeUnit.SECONDS) })
        }
    }

    @Test fun `an unsupported hello schema fails and a refused channel is reported without ending the connection`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO.replace("schema_version\":1", "schema_version\":9"))
            assertFailure(SessionFailure.Protocol, withTimeout(3000) { attempt.result.await() })
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO)
            connection.text("""{"type":"subscribed","request_id":"live-1","channels":[],"rejected":[{"channel":"sessions","code":"forbidden","message":"private"}]}""")
            val (accepted, rejected) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(emptySet<String>(), accepted); assertEquals(mapOf("sessions" to "forbidden"), rejected)
            delay(300)
            assertFalse("a refused channel is the owner's to act on, not a failure of the socket", attempt.result.isCompleted)
            assertTrue(attempt.sessions.tryReceive().isFailure)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `several channels are subscribed together, their snapshots and events arrive per channel`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api), setOf("tasks", "scans", "sessions"))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO_ADMIN.replace("\"jobs\"", "\"jobs\""))
            assertEquals("""{"type":"subscribe","request_id":"live-1","channels":["scans","sessions","tasks"]}""", withContext(Dispatchers.IO) { connection.messages.poll(3, TimeUnit.SECONDS) })
            connection.text("""{"type":"subscribed","request_id":"live-1","channels":["scans","sessions","tasks"]}""")
            connection.text(EventsProtocolTest.snapshot(channel = "scans")); connection.text(EventsProtocolTest.snapshot()); connection.text("""{"type":"snapshot","channel":"tasks","timestamp":"2026-10-12T00:00:00Z","data":[]}""")
            val (accepted, _) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(setOf("scans", "sessions", "tasks"), accepted)
            connection.text("""{"type":"event","channel":"tasks","event":"task.updated","event_id":"t1","timestamp":"2026-10-12T00:00:01Z","data":{"key":"x"}}""")
            connection.text(EventsProtocolTest.REPLACEMENT)
            withTimeout(3000) { while (attempt.control.events.size < 2) delay(10) }
            assertEquals(listOf("scans", "sessions", "tasks"), attempt.control.snapshots.toList())
            assertEquals(listOf("tasks", "sessions"), attempt.control.events.toList())
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `a channel the hello did not offer is reported as not offered`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api), setOf("sessions", "scans"))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO)
            connection.text("""{"type":"subscribed","request_id":"live-1","channels":["sessions"]}""")
            val (accepted, rejected) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(setOf("sessions"), accepted); assertEquals(mapOf("scans" to "not_offered"), rejected)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `an acknowledgement for a channel that was not asked for or not offered fails`() = runBlocking {
        for (ack in listOf(
            """{"type":"subscribed","request_id":"live-1","channels":["sessions","jobs"]}""",
            """{"type":"subscribed","request_id":"live-9","channels":["sessions"]}""",
        )) fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO_ADMIN); connection.text(ack)
            assertFailure(SessionFailure.Protocol, withTimeout(3000) { attempt.result.await() })
        }
    }

    @Test fun `a channel added while connected is subscribed with a second frame carrying everything held`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection()
            connection.text(EventsProtocolTest.HELLO_ADMIN)
            assertEquals("""{"type":"subscribe","request_id":"live-1","channels":["sessions"]}""", withContext(Dispatchers.IO) { connection.messages.poll(3, TimeUnit.SECONDS) })
            connection.text(EventsProtocolTest.ACK); connection.text(EventsProtocolTest.snapshot())
            withTimeout(3000) { attempt.sessions.receive() }
            attempt.control.wantedState.value = setOf("sessions", "tasks")
            assertEquals("""{"type":"subscribe","request_id":"live-2","channels":["sessions","tasks"]}""", withContext(Dispatchers.IO) { connection.messages.poll(3, TimeUnit.SECONDS) })
            connection.text("""{"type":"subscribed","request_id":"live-2","channels":["sessions","tasks"]}""")
            val first = withTimeout(3000) { attempt.control.subscribed.receive() }
            val second = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(setOf("sessions"), first.first); assertEquals(setOf("sessions", "tasks"), second.first)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `a quiet socket stays alive while the server answers pings, and a silent server ends it`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api, keepAliveMillis = 300))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            val before = attempt.control.activity.get()
            delay(1_500)
            assertFalse(attempt.result.isCompleted)
            assertTrue("each answered keep-alive is a sign of life for the watchdog", attempt.control.activity.get() >= before + 3)
            server.answerPings = false
            assertFailure(SessionFailure.Connection, withTimeout(5_000) { attempt.result.await() })
        }
    }

    @Test fun `fragmented messages share the total size bound`() = runBlocking {
        fixture { server, api ->
            val attempt = attempt(KtorLiveTransport(api))
            val connection = server.connection(); connection.ready(); attempt.sessions.receive()
            runCatching {
                val message = """{"type":"future","padding":"${"x".repeat(1_200_000)}"}""".toByteArray()
                connection.frame(1, message.copyOfRange(0, 600_000), final = false)
                connection.frame(0, message.copyOfRange(600_000, message.size))
            }
            assertTrue(withTimeout(3000) { attempt.result.await() } is SessionTransportException)
            assertTrue(attempt.sessions.tryReceive().isFailure)
        }
    }

    private suspend fun fixture(block: suspend CoroutineScope.(TlsEventsFixture, ApiClient) -> Unit) = coroutineScope {
        TlsEventsFixture().use { server ->
            val api = ApiClient(server.origin, "fixture-access", server.engine())
            try { withTimeout(30_000) { block(server, api) } } finally { api.close() }
        }
    }
    /** What the owner hears from one connection: [sessions] gets a unit for each cue on the sessions channel. */
    private data class Attempt(val job: Job, val result: CompletableDeferred<Throwable>, val sessions: Channel<Unit>, val control: Recorder)
    private class Recorder(val sessions: Channel<Unit>, wanted: Set<String>) : LiveControl {
        val wantedState = kotlinx.coroutines.flow.MutableStateFlow(wanted)
        val subscribed = Channel<Pair<Set<String>, Map<String, String>>>(Channel.UNLIMITED)
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<String>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val activity = java.util.concurrent.atomic.AtomicInteger()
        override fun wanted() = wantedState.value
        override val wantedChanges = wantedState
        override fun onSubscribed(accepted: Set<String>, rejected: Map<String, String>) { subscribed.trySend(accepted to rejected) }
        override fun onSnapshot(channel: String) { snapshots += channel; if (channel == "sessions") sessions.trySend(Unit) }
        override fun onEvent(channel: String) { events += channel; if (channel == "sessions") sessions.trySend(Unit) }
        override fun onActivity() { activity.incrementAndGet() }
    }
    private fun CoroutineScope.attempt(transport: LiveTransport, wanted: Set<String> = setOf("sessions")): Attempt {
        val result = CompletableDeferred<Throwable>()
        val sessions = Channel<Unit>(Channel.UNLIMITED)
        val recorder = Recorder(sessions, wanted)
        val job = launch {
            try { transport.connect(recorder); result.complete(AssertionError("Unexpected normal completion")) }
            catch (failure: Throwable) { result.complete(failure) }
        }
        return Attempt(job, result, sessions, recorder)
    }
    private suspend fun TlsEventsFixture.connection(): TlsEventsFixture.Connection = withContext(Dispatchers.IO) {
        checkNotNull(connections.poll(3, TimeUnit.SECONDS)) { "No TLS upgrade connection; peer failure types: $peerFailures; HTTP requests: ${requests.size}" }
    }
    private fun TlsEventsFixture.Connection.ready() {
        text(EventsProtocolTest.HELLO); text(EventsProtocolTest.ACK); text(EventsProtocolTest.snapshot())
    }
    private fun assertFailure(expected: SessionFailure, error: Throwable) {
        assertTrue("Expected sanitized transport failure", error is SessionTransportException)
        assertEquals(expected, (error as SessionTransportException).failure)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.toString().contains("private"))
        assertFalse(error.toString().contains("silo.ticket"))
    }
}
