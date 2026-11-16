package com.bloemstudios.admin.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One attempt. The owner controls reconnect, fallback and foreground lifetime. */
fun interface SessionTransport {
    /**
     * [onResync] is called when the sessions list has to be read again: once the connection is live and after each
     * `sessions.replaced`. The frames' rows are not data (older shape, at most 200 rows): the owner answers each call by
     * reading the full list, and the call returns once that read is done.
     */
    suspend fun collect(onResync: suspend () -> Unit)

    /**
     * True while the socket cannot be reached for a while (three failed connects in a row, or no socket at all): the
     * owner then reads on a slow timer, and only then. Transports that do not track it never ask for polling.
     */
    val fallback: StateFlow<Boolean> get() = NEVER_FALLBACK

    /** False while a shared connection is down or reconnecting, so the owner stops presenting its rows as live. Null: not tracked. */
    val link: Flow<Boolean>? get() = null

}

private val NEVER_FALLBACK: StateFlow<Boolean> = MutableStateFlow(false)

enum class RealtimeState { Connecting, Live, Reconnecting, Unavailable, SignInRequired }

/**
 * [AccessChanged] is the server's access_changed frame or close 4001: the login still holds but the access the ticket
 * was minted under changed, so the owner reconnects with a new ticket. It is not a sign-in failure.
 */
enum class SessionFailure { SignInRequired, Unsupported, Protocol, Connection, Deadline, AccessChanged }

/** Intentionally retains no upstream message, cause, request, headers or ticket. */
class SessionTransportException(val failure: SessionFailure) : Exception("Sessions transport: ${failure.name}")

internal fun sessionFailureForStatus(status: Int): SessionFailure = when (status) {
    401, 403 -> SessionFailure.SignInRequired
    404, 405, 501 -> SessionFailure.Unsupported
    else -> SessionFailure.Connection
}
