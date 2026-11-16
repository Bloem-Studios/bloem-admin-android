package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.NoBody
import com.bloemstudios.admin.network.api.ORGANIZATION_LIVE_SOCKET_PATH
import com.bloemstudios.admin.network.api.ORGANIZATION_LIVE_TICKET_PATH
import com.bloemstudios.admin.network.api.OrganizationLiveTicket
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Mint with the pass: a 401 renews the pass through the client's own recovery and asks once more. Throws [SessionTransportException]. */
internal suspend fun ApiClient.mintOrganizationLiveTicket(principalEpoch: Long): OrganizationLiveTicket {
    try {
        val result = request<OrganizationLiveTicket, NoBody>(
            ORGANIZATION_LIVE_TICKET_PATH, HttpMethod.Post, NoBody, requiredPrincipalEpoch = principalEpoch,
        )
        if (currentPrincipalEpoch != principalEpoch) throw SessionTransportException(SessionFailure.SignInRequired)
        if (result.protocol != OrganizationLiveProtocol.PROTOCOL || !Regex("^[A-Za-z0-9_-]{43}$").matches(result.ticket)) {
            throw SessionTransportException(SessionFailure.Protocol)
        }
        return result
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: SessionTransportException) {
        throw failure
    } catch (failure: ApiError) {
        throw SessionTransportException(sessionFailureForStatus(failure.statusCode))
    } catch (_: Exception) {
        throw SessionTransportException(if (currentPrincipalEpoch != principalEpoch) SessionFailure.SignInRequired else SessionFailure.Connection)
    }
}

/**
 * One connection to the organization live socket (`bloem-live.v1`) per call. Each [connect]
 * mints a fresh ticket with the context pass, and subscribes to the wanted channels the ticket lists; a channel it does not
 * list is reported as not offered, which is how a page learns to keep what it did before. Channels are subscribed and
 * unsubscribed as the wanted set changes.
 *
 * What it tells the owner: every `resync` is a cue to read the channel's list (the first one of a subscription is the
 * connection's snapshot), every `change` is a cue too. The close codes decide the next step: 4401, 4403 (the pass is
 * renewed by the next ticket's mint), 4408 and 1012 reconnect at once; anything else backs off.
 */
class OrganizationLiveTransport(
    private val api: ApiClient,
    private val initialTimeoutMillis: Long = 15_000,
    /** The client pings this often; the engine closes a session whose ping goes unanswered. A live answer is a sign of life. */
    private val keepAliveMillis: Long = 20_000,
) : LiveTransport {
    override suspend fun connect(control: LiveControl): Unit = coroutineScope {
        val epoch = api.currentPrincipalEpoch
        fun checkAuthority() {
            if (api.currentPrincipalEpoch != epoch) throw SessionTransportException(SessionFailure.SignInRequired)
        }
        try {
            coroutineScope {
                val authority = launch(start = CoroutineStart.UNDISPATCHED) {
                    api.principalEpoch.first { it != epoch }
                    throw SessionTransportException(SessionFailure.SignInRequired)
                }
                val closed = launch(start = CoroutineStart.UNDISPATCHED) {
                    api.isClosed.first { it }
                    throw SessionTransportException(SessionFailure.Connection)
                }
                // HttpTimeout does not cover Ktor upgrades. This bound ends with the first resync (or when nothing is subscribed).
                val initialDeadline = launch {
                    delay(initialTimeoutMillis)
                    throw SessionTransportException(SessionFailure.Deadline)
                }
                try {
                    checkAuthority()
                    val ticket = api.mintOrganizationLiveTicket(epoch)
                    checkAuthority()
                    val offered = ticket.channels.toSet()
                    val socketUrl = URLBuilder(api.origin.resolve(ORGANIZATION_LIVE_SOCKET_PATH)).apply { protocol = URLProtocol.WSS }.build()
                    api.http.webSocket(request = {
                        url.takeFrom(socketUrl)
                        header(HttpHeaders.SecWebSocketProtocol, "${OrganizationLiveProtocol.PROTOCOL}, ticket.${ticket.ticket}")
                        timeout {
                            connectTimeoutMillis = initialTimeoutMillis
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }) {
                        try {
                            checkAuthority()
                            if (call.response.headers[HttpHeaders.SecWebSocketProtocol] != OrganizationLiveProtocol.PROTOCOL) {
                                throw SessionTransportException(SessionFailure.Protocol)
                            }
                            val socket = this
                            socket.pingIntervalMillis = keepAliveMillis
                            socket.timeoutMillis = keepAliveMillis
                            control.onActivity()
                            // The server's heartbeat comes every 25 s; an answered ping proves the socket on a quieter stretch too.
                            launch { while (true) { delay(keepAliveMillis); control.onActivity() } }

                            // What this connection has subscribed, what the server will not serve, and which channels have had their resync.
                            val state = Mutex()
                            val subscribed = LinkedHashSet<String>()
                            val refused = LinkedHashMap<String, String>()
                            val resynced = HashSet<String>()

                            // Brings the server's subscription to [wanted]: only what the ticket lists is asked for, and a channel
                            // nobody shows any more is dropped. Nothing is sent before there is something to subscribe.
                            suspend fun reconcile(wanted: Set<String>) = state.withLock {
                                for (channel in wanted) if (channel !in offered) refused.putIfAbsent(channel, "not_offered")
                                val drop = subscribed.filter { it !in wanted }
                                val ask = wanted.filter { it in offered && it !in refused && it !in subscribed }
                                if (drop.isNotEmpty()) {
                                    send(Frame.Text(OrganizationLiveProtocol.unsubscribe(drop)))
                                    subscribed.removeAll(drop.toSet()); resynced.removeAll(drop.toSet())
                                }
                                if (ask.isNotEmpty()) {
                                    send(Frame.Text(OrganizationLiveProtocol.subscribe(ask)))
                                    subscribed.addAll(ask)
                                }
                                control.onSubscribed(subscribed.toSet(), refused.filterKeys { it in wanted })
                                if (subscribed.isEmpty()) initialDeadline.cancel()
                            }
                            val subscriber = launch { control.wantedChanges.collect { reconcile(it) } }

                            for (message in incoming) {
                                checkAuthority()
                                control.onActivity()
                                if (message !is Frame.Text) throw SessionTransportException(SessionFailure.Protocol)
                                when (val frame = OrganizationLiveProtocol.decode(message.readText())) {
                                    is OrganizationLiveFrame.Resync -> {
                                        // true: the first of this subscription, which is the connection's snapshot. false: a later one
                                        // (a burst the server folded, or a lost signal): the page must read again, so it is a change.
                                        val first = state.withLock { if (frame.channel in subscribed) resynced.add(frame.channel) else null }
                                        when (first) {
                                            null -> Unit
                                            true -> { initialDeadline.cancel(); control.onSnapshot(frame.channel) }
                                            false -> control.onEvent(frame.channel)
                                        }
                                    }
                                    is OrganizationLiveFrame.Change -> if (state.withLock { frame.channel in subscribed }) control.onEvent(frame.channel)
                                    is OrganizationLiveFrame.Error -> {
                                        val channel = frame.channel
                                        if (channel != null) state.withLock {
                                            if (subscribed.remove(channel)) {
                                                resynced.remove(channel)
                                                refused[channel] = frame.code
                                                control.onSubscribed(subscribed.toSet(), refused.filterKeys { it in control.wanted() })
                                                if (subscribed.isEmpty()) initialDeadline.cancel()
                                            }
                                        }
                                    }
                                    OrganizationLiveFrame.Heartbeat, OrganizationLiveFrame.Ignored -> Unit
                                }
                            }
                            subscriber.cancel()
                            throw SessionTransportException(failureForClose(closeReason.await()?.code?.toInt()))
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
        }
    }

    internal companion object {
        /**
         * 4401 (a new ticket), 4403 (the pass no longer holds: the next ticket's mint renews it, and answers 401 if it cannot),
         * 4408 (the pass or the connection ran out) and 1012 (the server restarts): one new ticket at once. [SessionFailure.AccessChanged]
         * is that, and a repeat before the connection proves itself backs off. 4429, 1013 and the rest back off.
         */
        fun failureForClose(code: Int?): SessionFailure = when (code) {
            4401, 4403, 4408, 1012 -> SessionFailure.AccessChanged
            else -> SessionFailure.Connection
        }
    }
}
