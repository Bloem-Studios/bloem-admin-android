package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.listSessions
import com.bloemstudios.admin.streams.AdminSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The playback sessions of one server, kept current by the server's shared events socket ([ApiClient.live], the
 * `sessions` channel) and shared across overlapping destinations. The socket only cues a read; there is no timer here:
 * while the socket is up nothing is read on a clock, and only while it cannot be had (see [LiveConnection.fallback]),
 * or the server has no `sessions` channel, is the list read every [LiveConnection.fallbackPollMillis].
 */
class LiveSessionsRepository internal constructor(
    private val api: ApiClient,
    private val live: LiveConnection = api.live,
    private val read: suspend () -> List<AdminSession> = { api.listSessions() },
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _sessions = MutableStateFlow<List<AdminSession>?>(null)
    val sessions = _sessions.asStateFlow()
    private val _state = MutableStateFlow(RealtimeState.Unavailable)
    val state = _state.asStateFlow()
    private val _error = MutableStateFlow<SessionFailure?>(null)
    val error = _error.asStateFlow()

    private val ownership = Mutex()
    // All fields below and all flow commits use the API's authority monitor. The
    // lock order is ownership -> authority; neither job joins nor callbacks run in it.
    private var owners = 0
    private var generation = 0L
    private var revision = 0L
    private var terminalEpoch: Long? = null
    private var closed = false
    private var runner: Job? = null
    private var workScope: CoroutineScope? = null
    private var reading: Deferred<Boolean>? = null
    private val invalidation: () -> Unit = { invalidateAuthority() }

    init { synchronized(api.sessionAuthorityLock) {
        api.sessionInvalidations += invalidation
        if (api.isClosed.value) close()
    } }

    /** Retain for the visible STARTED lifetime; cancellation releases this consumer. */
    suspend fun observe(): Nothing {
        ownership.withLock { synchronized(api.sessionAuthorityLock) {
            if (!closed && ++owners == 1) startLocked()
        } }
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { ownership.withLock {
            synchronized(api.sessionAuthorityLock) {
                if (owners > 0 && --owners == 0) {
                    generation++
                    runner?.cancel()
                    workScope = null
                    reading = null
                    if (_state.value != RealtimeState.SignInRequired) _state.value = RealtimeState.Unavailable
                }
            }
        } } }
    }

    // Caller holds authority monitor. Canceled predecessors are joined outside it,
    // including during rapid stop/start or principal changes.
    private fun startLocked() {
        if (closed || owners == 0 || terminalEpoch == api.currentPrincipalEpoch) return
        val previous = runner
        previous?.cancel()
        val epoch = api.currentPrincipalEpoch
        val owner = ++generation
        _state.value = RealtimeState.Connecting
        runner = scope.launch {
            // A canceled intermediate owner must not let its successor bypass
            // an older connection whose cancellation cleanup is still running.
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            coroutineScope {
                synchronized(api.sessionAuthorityLock) {
                    if (!current(epoch, owner)) return@coroutineScope
                    workScope = this
                }
                launch { refresh() }
                launch { live.retain(setOf(LiveChannels.SESSIONS)) }
                launch { drive(epoch, owner) }
            }
        }
    }

    private fun current(epoch: Long, owner: Long): Boolean =
        !closed && !api.isClosed.value && owners > 0 && generation == owner &&
            api.currentPrincipalEpoch == epoch && terminalEpoch != epoch

    /** Follows the shared socket: its state, the cues on `sessions`, and the slow read while there is no socket. */
    private suspend fun drive(epoch: Long, owner: Long): Unit = coroutineScope {
        launch {
            combine(live.status, live.offer) { status, offer -> status to offer }.distinctUntilChanged().collect { (status, offer) ->
                var signIn = false
                synchronized(api.sessionAuthorityLock) {
                    if (!current(epoch, owner)) return@collect
                    val refusal = offer.rejected[LiveChannels.SESSIONS]
                    when {
                        status == LiveStatus.SignInRequired -> signIn = true
                        // The account may not watch sessions here: the same as a refused login, not a missing feature.
                        refusal == "forbidden" || refusal == "profile_required" -> signIn = true
                        status == LiveStatus.Unavailable || refusal != null -> {
                            _error.value = SessionFailure.Unsupported; _state.value = RealtimeState.Unavailable
                        }
                        status == LiveStatus.Reconnecting -> { _error.value = SessionFailure.Connection; _state.value = RealtimeState.Reconnecting }
                    }
                }
                if (signIn) terminate(epoch, owner)
            }
        }
        // A cue (the snapshot of a new connection, or sessions.replaced) is answered with a full read on the shared read
        // path (epoch, authority and mutation fences intact), then Live is published. A failed read ends the connection, so
        // backoff and the next snapshot read again.
        launch {
            live.signal(LiveChannels.SESSIONS).collectLatest { cue ->
                if (cue == 0L) return@collectLatest
                // Queue behind and fence any read already in flight: it predates this cue.
                // A mutation fence can discard this read's rows; read again until some read has published.
                var reads = 0
                do {
                    if (!refresh(afterMutation = true)) { live.restart(); return@collectLatest }
                } while (++reads < 3 && synchronized(api.sessionAuthorityLock) { _sessions.value == null && current(epoch, owner) })
                synchronized(api.sessionAuthorityLock) {
                    // Rows must exist: a fenced read publishes nothing, so stay out of Live.
                    if (current(epoch, owner) && _sessions.value != null && live.status.value == LiveStatus.Live &&
                        LiveChannels.SESSIONS in live.offer.value.accepted) {
                        _error.value = null
                        _state.value = RealtimeState.Live
                    }
                }
            }
        }
        launch {
            combine(live.fallback, live.offer) { fallback, offer -> fallback || LiveChannels.SESSIONS in offer.rejected }
                .distinctUntilChanged()
                .collectLatest { slow ->
                    while (slow) {
                        delay(live.fallbackPollMillis)
                        refresh()
                    }
                }
        }
    }

    /**
     * Coalesced foreground read. Loss of its owner/authority ends reconciliation normally
     * for an active action caller; cancellation of that caller still propagates.
     */
    suspend fun refresh() { refresh(afterMutation = false) }

    /**
     * A confirmed mutation invalidates reads already in flight. Queue a new read
     * after their completion; ordinary refresh callers can join this barrier.
     * The queue belongs to the foreground owner, so actions retain refresh's
     * no-owner and cancellation contract without replaying a mutation.
     */
    suspend fun reconcileAfterMutation() { refresh(afterMutation = true) }

    /** True unless the read itself failed. No owner or a fenced read count as nothing to report. */
    private suspend fun refresh(afterMutation: Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val pending = synchronized(api.sessionAuthorityLock) {
            val epoch = api.currentPrincipalEpoch
            val owner = generation
            val activeScope = workScope ?: return true
            if (!current(epoch, owner)) return true
            val predecessor = reading?.takeIf { !it.isCompleted }
            if (afterMutation) revision++ // Fence pre-mutation publication immediately.
            if (!afterMutation && predecessor != null) predecessor else activeScope.async(start = CoroutineStart.LAZY) {
                if (afterMutation) predecessor?.join()
                ensureActive()
                val before = synchronized(api.sessionAuthorityLock) { revision }
                try {
                    val rows = read()
                    synchronized(api.sessionAuthorityLock) {
                        if (current(epoch, owner) && revision == before) {
                            revision++
                            _sessions.value = rows
                            // A REST success cannot conceal a socket failure/source state.
                            if (_state.value == RealtimeState.Live) _error.value = null
                        }
                    }
                    true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiError && e.statusCode in setOf(401, 403)) terminate(epoch, owner)
                    else synchronized(api.sessionAuthorityLock) {
                        if (current(epoch, owner) && revision == before) _error.value = SessionFailure.Connection
                    }
                    false
                }
            }.also { reading = it }
        }
        pending.start()
        return try { pending.await() }
        catch (_: CancellationException) {
            // This Deferred belongs to the foreground runner, not the action caller.
            // Preserve confirmed mutation outcomes when only repository work stops.
            currentCoroutineContext().ensureActive()
            true
        }
    }

    private fun terminate(epoch: Long, owner: Long) {
        synchronized(api.sessionAuthorityLock) {
            if (!current(epoch, owner)) return
            terminalEpoch = epoch
            generation++; revision++
            _sessions.value = null
            _error.value = SessionFailure.SignInRequired
            _state.value = RealtimeState.SignInRequired
            runner?.cancel(); workScope = null; reading = null
        }
        // The binding atomically validates this epoch and commits gate state, accepting
        // same-authority token refresh but rejecting a replaced principal even here.
        scope.launch {
            if (api.currentPrincipalEpoch == epoch && !api.isClosed.value) api.onTerminalAuthorityLost?.invoke(epoch)
        }
    }

    internal fun invalidateAuthority() = synchronized(api.sessionAuthorityLock) {
        generation++; revision++
        runner?.cancel(); workScope = null; reading = null
        terminalEpoch = null
        _sessions.value = null; _error.value = null
        _state.value = RealtimeState.Unavailable
        if (api.isClosed.value) close() else startLocked()
    }

    fun close() = synchronized(api.sessionAuthorityLock) {
        if (!closed) {
            closed = true; generation++; revision++
            api.sessionInvalidations -= invalidation
            _sessions.value = null; _error.value = null; _state.value = RealtimeState.Unavailable
            workScope = null; reading = null
            scope.cancel()
        }
    }
}
