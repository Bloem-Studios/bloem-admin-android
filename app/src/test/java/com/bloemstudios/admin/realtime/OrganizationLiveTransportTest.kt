package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.security.Provider
import java.security.Security
import java.util.concurrent.TimeUnit

/** The organization live socket against a raw TLS peer: real upgrade, subprotocol ticket, masking and close frames. */
class OrganizationLiveTransportTest {
    companion object {
        private var androidProvider: Provider? = null
        private var androidProviderPosition = 0

        @BeforeClass @JvmStatic fun useJvmFixtureProviders() {
            // Same reason as KtorLiveTransportTest: Robolectric's Conscrypt breaks the JSSE fixture.
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

        private const val STREAMS = OrganizationLiveChannels.STREAMS
        private const val PEOPLE = OrganizationLiveChannels.PEOPLE
        private const val ACTIVITY = OrganizationLiveChannels.ACTIVITY
    }

    private fun TlsEventsFixture.orgTicket(channels: List<String> = listOf(STREAMS, PEOPLE, ACTIVITY)) {
        selectedProtocol = "bloem-live.v1"
        ticketJson = { ticket ->
            """{"ticket":"$ticket","expires_at":"2026-11-16T12:00:30Z","protocol":"bloem-live.v1","channels":[${channels.joinToString(",") { "\"$it\"" }}],"max_connection_seconds":3600}"""
        }
    }

    @Test fun `the ticket is minted with the pass, the socket carries it only in the subprotocol, and nothing is sent before a subscribe`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection()
            val ticketRequest = checkNotNull(server.requests.poll())
            val upgrade = checkNotNull(server.requests.poll())
            assertEquals("POST /api/bloem/v2/admin/organization/live/ticket HTTP/1.1", ticketRequest.line)
            assertEquals("Bearer fixture-access", ticketRequest.headers["authorization"])
            assertEquals("GET /api/bloem/v2/organization/live/ws HTTP/1.1", upgrade.line)
            assertNull("the ticket is the credential here, not the pass", upgrade.headers["authorization"])
            assertEquals("bloem-live.v1, ticket.${server.minted.single()}", upgrade.headers["sec-websocket-protocol"])
            assertFalse(upgrade.line.contains("ticket"))
            // The subscribe is the first thing the client says.
            assertEquals("""{"op":"subscribe","channels":["org.streams"],"resume":null}""", connection.next())
            attempt.job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(3, TimeUnit.SECONDS) })
        }
    }

    @Test fun `the first resync is the snapshot, a later resync and every change are events, and heartbeats prove the socket is alive`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection(); connection.next()
            val before = attempt.control.activity.get()
            connection.text("""{"op":"heartbeat","seq":1}""")
            connection.text("""{"op":"resync","seq":2,"channel":"org.streams"}""")
            withTimeout(3000) { attempt.cues.receive() }
            assertEquals(listOf(STREAMS), attempt.control.snapshots); assertTrue(attempt.control.events.isEmpty())
            assertTrue("a heartbeat is a sign of life", attempt.control.activity.get() >= before + 2)
            connection.text("""{"op":"change","seq":3,"channel":"org.streams","id":"s1","rev":"2","kind":"updated"}""")
            withTimeout(3000) { attempt.cues.receive() }
            connection.text("""{"op":"resync","seq":4,"channel":"org.streams"}""")
            withTimeout(3000) { attempt.cues.receive() }
            assertEquals("the second resync is a read, not a repeat of the snapshot", listOf(STREAMS, STREAMS), attempt.control.events)
            assertEquals(listOf(STREAMS), attempt.control.snapshots)
            // A signal for a channel nobody here shows is not passed on.
            connection.text("""{"op":"change","seq":5,"channel":"org.people","id":"7","rev":"x","kind":"created"}""")
            connection.text("""{"op":"resync","seq":6,"channel":"org.activity"}""")
            delay(200)
            assertTrue(attempt.cues.tryReceive().isFailure)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `a channel the ticket does not list is not asked for and is reported as not offered`() = runBlocking {
        fixture { server, api ->
            server.orgTicket(channels = listOf(PEOPLE, ACTIVITY))
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS, PEOPLE))
            val connection = server.connection()
            assertEquals("""{"op":"subscribe","channels":["org.people"],"resume":null}""", connection.next())
            val (accepted, rejected) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(setOf(PEOPLE), accepted); assertEquals(mapOf(STREAMS to "not_offered"), rejected)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `a screen that wants only an unlisted channel sends no subscribe and the socket still counts as up`() = runBlocking {
        fixture { server, api ->
            server.orgTicket(channels = listOf(PEOPLE, ACTIVITY))
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection()
            val (accepted, rejected) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertTrue(accepted.isEmpty()); assertEquals(mapOf(STREAMS to "not_offered"), rejected)
            assertNull(connection.next(300))
            assertFalse(attempt.result.isCompleted)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `unknown_channel and unavailable errors keep the connection and move the channel to refused`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS, PEOPLE))
            val connection = server.connection(); connection.next()
            withTimeout(3000) { attempt.control.subscribed.receive() }
            connection.text("""{"op":"error","code":"unavailable","channel":"org.streams"}""")
            val (accepted, rejected) = withTimeout(3000) { attempt.control.subscribed.receive() }
            assertEquals(setOf(PEOPLE), accepted); assertEquals(mapOf(STREAMS to "unavailable"), rejected)
            assertFalse(attempt.result.isCompleted)
            // A refused channel is never asked for again on this connection.
            attempt.control.wantedState.value = setOf(STREAMS, PEOPLE, ACTIVITY)
            assertEquals("""{"op":"subscribe","channels":["org.activity"],"resume":null}""", connection.next())
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `channels are subscribed and unsubscribed as the screens come and go`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection()
            assertEquals("""{"op":"subscribe","channels":["org.streams"],"resume":null}""", connection.next())
            connection.text("""{"op":"resync","seq":1,"channel":"org.streams"}""")
            withTimeout(3000) { attempt.cues.receive() }
            // Moving to People: the new channel is asked for, the old one let go.
            attempt.control.wantedState.value = setOf(PEOPLE)
            assertEquals("""{"op":"unsubscribe","channels":["org.streams"]}""", connection.next())
            assertEquals("""{"op":"subscribe","channels":["org.people"],"resume":null}""", connection.next())
            // Back to Streams: its resync is a snapshot again, so the page reads what it missed.
            attempt.control.wantedState.value = setOf(STREAMS)
            connection.next(); connection.next()
            connection.text("""{"op":"resync","seq":2,"channel":"org.streams"}""")
            withTimeout(3000) { attempt.cues.receive() }
            assertEquals(listOf(STREAMS, STREAMS), attempt.control.snapshots)
            attempt.job.cancelAndJoin()
        }
    }

    @Test fun `the close codes end the attempt as the contract says`() = runBlocking {
        for ((code, expected) in listOf(
            4401 to SessionFailure.AccessChanged, 4403 to SessionFailure.AccessChanged, 4408 to SessionFailure.AccessChanged,
            1012 to SessionFailure.AccessChanged, 4429 to SessionFailure.Connection, 1013 to SessionFailure.Connection,
        )) fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection(); connection.next()
            connection.close(code)
            assertFailure(expected, withTimeout(5_000) { attempt.result.await() })
        }
    }

    @Test fun `ticket errors map to what the client does next`() = runBlocking {
        for ((status, expected) in listOf(
            401 to SessionFailure.SignInRequired, 403 to SessionFailure.SignInRequired, 404 to SessionFailure.Unsupported,
            429 to SessionFailure.Connection, 503 to SessionFailure.Connection,
        )) fixture { server, api ->
            server.orgTicket(); server.ticketStatus = status
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            assertFailure(expected, withTimeout(5_000) { attempt.result.await() })
            assertTrue("no socket is opened without a ticket", server.connections.isEmpty())
        }
    }

    @Test fun `a ticket of the wrong shape or protocol, and a socket that selects the wrong protocol, never subscribe`() = runBlocking {
        fixture { server, api ->
            server.orgTicket(); server.ticketJson = { """{"ticket":"short","protocol":"bloem-live.v1","channels":["org.streams"]}""" }
            assertFailure(SessionFailure.Protocol, withTimeout(5_000) { attempt(OrganizationLiveTransport(api), setOf(STREAMS)).result.await() })
            assertTrue(server.connections.isEmpty())
        }
        fixture { server, api ->
            server.orgTicket(); server.ticketJson = { ticket -> """{"ticket":"$ticket","protocol":"silo.events.v2","channels":["org.streams"]}""" }
            assertFailure(SessionFailure.Protocol, withTimeout(5_000) { attempt(OrganizationLiveTransport(api), setOf(STREAMS)).result.await() })
            assertTrue(server.connections.isEmpty())
        }
        for (selected in listOf("wrong", null)) fixture { server, api ->
            server.orgTicket(); server.selectedProtocol = selected
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            assertFailure(SessionFailure.Protocol, withTimeout(5_000) { attempt.result.await() })
        }
    }

    @Test fun `a frame the client cannot read ends the attempt`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api), setOf(STREAMS))
            val connection = server.connection(); connection.next()
            connection.text("""{"op":"change","channel":"org.streams"}""")
            assertFailure(SessionFailure.Protocol, withTimeout(5_000) { attempt.result.await() })
        }
    }

    @Test fun `a silent server that never answers the first subscribe ends the attempt at the deadline`() = runBlocking {
        fixture { server, api ->
            server.orgTicket()
            val attempt = attempt(OrganizationLiveTransport(api, initialTimeoutMillis = 400), setOf(STREAMS))
            server.connection()
            assertFailure(SessionFailure.Deadline, withTimeout(5_000) { attempt.result.await() })
        }
    }

    private suspend fun fixture(block: suspend CoroutineScope.(TlsEventsFixture, ApiClient) -> Unit) = coroutineScope {
        TlsEventsFixture().use { server ->
            val api = ApiClient(server.origin, "fixture-access", server.engine())
            try { withTimeout(30_000) { block(server, api) } } finally { api.close() }
        }
    }

    /** What the owner hears from one connection: [cues] gets a unit for each snapshot or event of any channel. */
    private data class Attempt(val job: Job, val result: CompletableDeferred<Throwable>, val cues: Channel<Unit>, val control: Recorder)
    private class Recorder(val cues: Channel<Unit>, wanted: Set<String>) : LiveControl {
        val wantedState = MutableStateFlow(wanted)
        val subscribed = Channel<Pair<Set<String>, Map<String, String>>>(Channel.UNLIMITED)
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<String>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val activity = java.util.concurrent.atomic.AtomicInteger()
        override fun wanted() = wantedState.value
        override val wantedChanges = wantedState
        override fun onSubscribed(accepted: Set<String>, rejected: Map<String, String>) { subscribed.trySend(accepted to rejected) }
        override fun onSnapshot(channel: String) { snapshots += channel; cues.trySend(Unit) }
        override fun onEvent(channel: String) { events += channel; cues.trySend(Unit) }
        override fun onActivity() { activity.incrementAndGet() }
    }
    private fun CoroutineScope.attempt(transport: LiveTransport, wanted: Set<String>): Attempt {
        val result = CompletableDeferred<Throwable>()
        val cues = Channel<Unit>(Channel.UNLIMITED)
        val recorder = Recorder(cues, wanted)
        val job = launch {
            try { transport.connect(recorder); result.complete(AssertionError("Unexpected normal completion")) }
            catch (failure: Throwable) { result.complete(failure) }
        }
        return Attempt(job, result, cues, recorder)
    }
    private suspend fun TlsEventsFixture.connection(): TlsEventsFixture.Connection = withContext(Dispatchers.IO) {
        checkNotNull(connections.poll(3, TimeUnit.SECONDS)) { "No TLS upgrade connection; peer failure types: $peerFailures; HTTP requests: ${requests.size}" }
    }
    /** The next message the client sent, waiting off the test's own thread so the transport can run. */
    private suspend fun TlsEventsFixture.Connection.next(millis: Long = 3_000): String? = withContext(Dispatchers.IO) { messages.poll(millis, TimeUnit.MILLISECONDS) }
    private fun assertFailure(expected: SessionFailure, error: Throwable) {
        assertTrue("Expected sanitized transport failure, got $error", error is SessionTransportException)
        assertEquals(expected, (error as SessionTransportException).failure)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.toString().contains("ticket."))
    }
}
