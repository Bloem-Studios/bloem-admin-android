package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.EVENTS_SOCKET_PATH
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One events-socket connection per call, over Silo's `silo.events.v2` protocol. Reuse one instance per owner/server.
 * Each [connect] mints a fresh, single-use ticket and subscribes to the channels [LiveControl.wanted] names, and again
 * whenever that set changes (a subscribe replaces the set, and the server answers it with a snapshot per channel).
 */
class KtorLiveTransport(
    private val api: ApiClient,
    private val initialTimeoutMillis: Long = 15_000,
    /** The client pings this often; the engine closes a session whose ping goes unanswered. A live answer is a sign of life. */
    private val keepAliveMillis: Long = 20_000,
) : LiveTransport {
    private val ownership = Mutex()
    private val generation = AtomicLong()
    private val activeAttempt = AtomicReference<Job?>(null)

    override suspend fun connect(control: LiveControl): Unit = coroutineScope {
        val owner = currentCoroutineContext().job
        val attemptGeneration = ownership.withLock {
            activeAttempt.get()?.cancelAndJoin()
            activeAttempt.set(owner)
            generation.incrementAndGet()
        }
        val epoch = api.currentPrincipalEpoch
        fun checkAuthority() {
            owner.ensureActive()
            if (api.currentPrincipalEpoch != epoch) throw SessionTransportException(SessionFailure.SignInRequired)
            if (generation.get() != attemptGeneration) throw CancellationException("Events attempt replaced")
        }
        try {
            coroutineScope {
                // Watchers terminate idle connections too, including pending ticket/upgrade I/O.
                val authority = launch(start = CoroutineStart.UNDISPATCHED) {
                    api.principalEpoch.first { it != epoch }
                    throw SessionTransportException(SessionFailure.SignInRequired)
                }
                val closed = launch(start = CoroutineStart.UNDISPATCHED) {
                    api.isClosed.first { it }
                    throw SessionTransportException(SessionFailure.Connection)
                }
                // HttpTimeout does not cover Ktor upgrades. This bound ends with the first snapshot (or an answer that accepts nothing).
                val initialDeadline = launch {
                    delay(initialTimeoutMillis)
                    throw SessionTransportException(SessionFailure.Deadline)
                }
                try {
                    checkAuthority()
                    val ticket = api.mintEventsSocketTicket(epoch)
                    checkAuthority()
                    val socketUrl = URLBuilder(api.origin.resolve(EVENTS_SOCKET_PATH)).apply {
                        protocol = URLProtocol.WSS
                    }.build()
                    api.http.webSocket(request = {
                        url.takeFrom(socketUrl)
                        header(HttpHeaders.SecWebSocketProtocol, "${EventsProtocol.PROTOCOL}, silo.ticket.${ticket.ticket}")
                        timeout {
                            connectTimeoutMillis = initialTimeoutMillis
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }) {
                        try {
                            checkAuthority()
                            if (call.response.headers[HttpHeaders.SecWebSocketProtocol] != EventsProtocol.PROTOCOL) {
                                throw SessionTransportException(SessionFailure.Protocol)
                            }
                            val socket = this
                            socket.pingIntervalMillis = keepAliveMillis
                            socket.timeoutMillis = keepAliveMillis
                            control.onActivity()
                            // A keep-alive that is still being answered proves a quiet socket is alive. The engine closes the
                            // session when a ping goes unanswered, so reaching the next tick means the last one was.
                            launch { while (true) { delay(keepAliveMillis); control.onActivity() } }

                            var hello = false
                            var acknowledged = false
                            var requestSequence = 0
                            val pending = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
                            var available = emptySet<String>()
                            var lastSent: Set<String>? = null
                            suspend fun subscribe(wanted: Set<String>) {
                                lastSent = wanted
                                val id = "live-${++requestSequence}"
                                pending[id] = wanted
                                send(Frame.Text(EventsProtocol.subscribe(id, wanted.sorted())))
                            }
                            // Sends the wanted set again whenever it changes, once the server has said hello.
                            var subscriber: Job? = null
                            for (message in incoming) {
                                checkAuthority()
                                control.onActivity()
                                if (message !is Frame.Text) throw SessionTransportException(SessionFailure.Protocol)
                                when (val frame = EventsProtocol.decode(message.readText())) {
                                    is EventsFrame.Hello -> {
                                        if (hello) throw SessionTransportException(SessionFailure.Protocol)
                                        hello = true
                                        available = frame.available
                                        // The first subscription goes out before anything else is read, so its answer finds it
                                        // registered. Even an empty one: a connection that subscribes to nothing is closed.
                                        subscribe(control.wanted())
                                        subscriber = launch {
                                            control.wantedChanges.collect { wanted ->
                                                // A caller leaving only shrinks the set; the subscription stays as it is until the
                                                // next connection, and a channel nobody reads costs a few small frames.
                                                if (wanted.isNotEmpty() && !(lastSent ?: emptySet()).containsAll(wanted)) {
                                                    subscribe((lastSent ?: emptySet()) + wanted)
                                                }
                                            }
                                        }
                                    }
                                    is EventsFrame.Subscribed -> {
                                        if (!hello) throw SessionTransportException(SessionFailure.Protocol)
                                        val asked = pending.remove(frame.requestId) ?: throw SessionTransportException(SessionFailure.Protocol)
                                        // Only what was asked for can come back, and only what the hello offered can be accepted.
                                        if (!asked.containsAll(frame.channels) || !available.containsAll(frame.channels)) {
                                            throw SessionTransportException(SessionFailure.Protocol)
                                        }
                                        acknowledged = true
                                        // With nothing accepted no snapshot follows; otherwise the deadline ends at the first one.
                                        if (frame.channels.isEmpty()) initialDeadline.cancel()
                                        // A channel asked for and neither accepted nor refused is not offered either.
                                        val missing = asked - frame.channels - frame.rejected.keys
                                        control.onSubscribed(frame.channels, frame.rejected + missing.associateWith { "not_offered" })
                                    }
                                    is EventsFrame.Snapshot -> {
                                        if (!acknowledged) throw SessionTransportException(SessionFailure.Protocol)
                                        initialDeadline.cancel()
                                        checkAuthority()
                                        control.onSnapshot(frame.channel)
                                    }
                                    is EventsFrame.Event -> {
                                        if (!acknowledged) throw SessionTransportException(SessionFailure.Protocol)
                                        checkAuthority()
                                        control.onEvent(frame.channel)
                                    }
                                    EventsFrame.AccessChanged -> throw SessionTransportException(SessionFailure.AccessChanged)
                                    EventsFrame.Ignored -> Unit
                                }
                            }
                            subscriber?.cancel()
                            val reason = closeReason.await()
                            throw SessionTransportException(if (reason?.code?.toInt() == 4001) SessionFailure.AccessChanged else SessionFailure.Connection)
                        } finally {
                            // Scoped webSocket also cleans up; explicit cancellation bounds cleanup
                            // even if the peer never acknowledges a close frame.
                            cancel()
                        }
                    }
                } finally {
                    initialDeadline.cancel()
                    authority.cancel()
                    closed.cancel()
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SessionTransportException) {
            // Structured concurrency can attach raw sibling failures as suppressed exceptions.
            throw SessionTransportException(failure.failure)
        } catch (_: Exception) {
            throw SessionTransportException(SessionFailure.Connection)
        } finally {
            activeAttempt.compareAndSet(owner, null)
        }
    }
}
