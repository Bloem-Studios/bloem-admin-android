package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** What the app knows about the device: whether it is on screen, and when the network it uses changes. */
interface LiveEnvironment {
    /** True while the app is in the foreground (some activity is started). */
    val foreground: StateFlow<Boolean>
    /** Emits when the default network changes to another one. It does not emit for the network the app starts on. */
    val networkChanges: Flow<Unit>
}

/** An environment that is always in front and never changes network, for tests and for the time before the app installs one. */
object StaticLiveEnvironment : LiveEnvironment {
    override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
    override val networkChanges: Flow<Unit> = emptyFlow()
}

/** The environment new connections use. The Application installs the real one at start-up. */
object LiveEnvironments {
    @Volatile var current: LiveEnvironment = StaticLiveEnvironment
}

/** The numbers behind the client rules in the live-updates contract. */
data class LiveConfig(
    /** The socket is closed this long after the last screen using it, or the app, leaves the foreground. */
    val leaveGraceMillis: Long = 10_000,
    /** No frame, and no keep-alive proof, for this long means the socket is dead. */
    val watchdogMillis: Long = 60_000,
    val backoffBaseMillis: Long = 1_000,
    val backoffMaxMillis: Long = 60_000,
    /** This many failed connection attempts in a row start the fallback. */
    val fallbackAfterFailures: Int = 3,
    /** The slowest a visible screen is read while the fallback is on. */
    val fallbackPollMillis: Long = 60_000,
    /** A connection that lasts this long counts as working: the failure count starts again. A shorter one is a failure. */
    val stableMillis: Long = 5_000,
)

enum class LiveStatus { Idle, Connecting, Live, Reconnecting, SignInRequired, Unavailable }

/**
 * What the server said about the channels asked for: [accepted] are subscribed, [rejected] maps a channel to the
 * server's reason. [known] is false until the first answer of a connection.
 */
data class LiveOffer(
    val known: Boolean = false,
    val accepted: Set<String> = emptySet(),
    val rejected: Map<String, String> = emptyMap(),
    /** The accepted channels whose snapshot has arrived on this connection. */
    val hydrated: Set<String> = emptySet(),
)

/** What a transport tells its owner while one connection is open. */
interface LiveControl {
    /** The channels to be subscribed now. */
    fun wanted(): Set<String>
    /** Emits the wanted set whenever it changes (and once on collection). */
    val wantedChanges: Flow<Set<String>>
    fun onSubscribed(accepted: Set<String>, rejected: Map<String, String>)
    /** A channel's snapshot. The first one for a channel on a connection is a cue to re-read; later ones are not. */
    fun onSnapshot(channel: String)
    fun onEvent(channel: String)
    /** Anything that proves the connection is alive. */
    fun onActivity()
}

/** One socket connection, from the ticket to its end. Throws [SessionTransportException]. */
fun interface LiveTransport {
    suspend fun connect(control: LiveControl)
}

/**
 * The one events socket of one server. Every screen asks for the channels it shows through [retain]; the socket is open
 * only while some screen does and the app is in front. It closes [LiveConfig.leaveGraceMillis] after the last of them
 * (or the foreground) goes, reconnects with exponential backoff and full jitter, at once when the network changes or the
 * app comes back, and treats a connection with no sign of life for [LiveConfig.watchdogMillis] as dead. Three failed
 * connects in a row switch [fallback] on; it goes off as soon as a connection succeeds.
 *
 * The socket says only that something changed. [signal] counts those cues per channel, and the screen reads the REST
 * API again. Silo's protocol has no sequence numbers, so there is nothing to resume from: every new connection is
 * answered with a snapshot per channel, and that snapshot is itself a cue to read everything the channel covers.
 */
class LiveConnection internal constructor(
    private val api: ApiClient,
    private val transport: LiveTransport,
    parentScope: CoroutineScope,
    private val environment: LiveEnvironment = StaticLiveEnvironment,
    private val config: LiveConfig = LiveConfig(),
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val lock = Any()
    private val holders = HashMap<Int, Set<String>>()
    private var nextHolder = 0
    private val wantedState = MutableStateFlow<Set<String>>(emptySet())
    private val statusState = MutableStateFlow(LiveStatus.Idle)
    private val fallbackState = MutableStateFlow(false)
    private val offerState = MutableStateFlow(LiveOffer())
    private val ticks = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val kicks = MutableSharedFlow<Kick>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val activity = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val status: StateFlow<LiveStatus> = statusState.asStateFlow()
    /** True while visible screens that use a channel should read on a slow timer, because the socket cannot be had. */
    val fallback: StateFlow<Boolean> = fallbackState.asStateFlow()
    val offer: StateFlow<LiveOffer> = offerState.asStateFlow()
    /** The slowest fallback poll, for the screens. */
    val fallbackPollMillis: Long get() = config.fallbackPollMillis

    private enum class Kick { Network, Foreground, Restart }
    private class NetworkChange : Exception("Network changed")

    init {
        scope.launch { run() }
        scope.launch {
            var before = environment.foreground.value
            environment.foreground.collect { now ->
                if (now && !before) kicks.tryEmit(Kick.Foreground)
                before = now
            }
        }
        scope.launch { api.isClosed.first { it }; close() }
    }

    /**
     * The cues for [channel]: a counter that goes up each time the channel says something changed, and for each fresh
     * snapshot. Read the value when you start, and re-read when it differs.
     */
    fun signal(channel: String): StateFlow<Long> = ticks.getOrPut(channel) { MutableStateFlow(0L) }

    /**
     * Keep [channels] subscribed for as long as the caller stays suspended here. Counting is by caller: the same
     * channel asked for by two screens stays open until both are gone.
     */
    suspend fun retain(channels: Set<String>): Nothing {
        val id = synchronized(lock) {
            val key = nextHolder++
            holders[key] = channels
            wantedState.value = holders.values.flatten().toSet()
            key
        }
        try { awaitCancellation() }
        finally {
            synchronized(lock) {
                holders.remove(id)
                wantedState.value = holders.values.flatten().toSet()
            }
        }
    }

    /** How many callers hold [channel] now. */
    internal fun holdersOf(channel: String): Int = synchronized(lock) { holders.values.count { channel in it } }

    /**
     * Drop the connection and connect again after the usual backoff. A screen calls it when what it read after a cue
     * contradicts the socket (the read failed), so the next snapshot starts it afresh.
     */
    fun restart() { kicks.tryEmit(Kick.Restart) }

    fun close() {
        scope.cancel()
        statusState.value = LiveStatus.Idle
        fallbackState.value = false
        offerState.value = LiveOffer()
    }

    private suspend fun run() {
        combine(wantedState, environment.foreground) { wanted, foreground -> wanted.isNotEmpty() && foreground }
            .distinctUntilChanged()
            // Switching on is at once; switching off waits, so moving between screens or a quick app switch keeps the socket.
            .transformLatest { on -> if (on) emit(true) else { delay(config.leaveGraceMillis); emit(false) } }
            .distinctUntilChanged()
            .collectLatest { on ->
                if (on) coroutineScope {
                    // Listening for network changes is part of holding a socket, so nothing wakes the app in the background.
                    launch { environment.networkChanges.collect { kicks.tryEmit(Kick.Network) } }
                    session()
                } else reset()
            }
    }

    private fun reset() {
        statusState.value = LiveStatus.Idle
        fallbackState.value = false
        offerState.value = LiveOffer()
    }

    private suspend fun session() {
        var failures = 0
        var accessChanges = 0
        statusState.value = LiveStatus.Connecting
        fallbackState.value = false
        while (true) {
            val epoch = api.currentPrincipalEpoch
            var established = false
            val ended: Throwable = try {
                attempt(
                    onEstablished = {
                        established = true
                        fallbackState.value = false
                        statusState.value = LiveStatus.Live
                    },
                    onStable = {
                        failures = 0
                        accessChanges = 0
                    },
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { e }

            if (ended is NetworkChange) {
                statusState.value = LiveStatus.Reconnecting
                continue
            }
            val failure = (ended as? SessionTransportException)?.failure ?: SessionFailure.Connection
            when (failure) {
                SessionFailure.SignInRequired -> {
                    // Another login already replaced the one that failed: just connect again under it.
                    if (api.currentPrincipalEpoch != epoch) continue
                    statusState.value = LiveStatus.SignInRequired
                    fallbackState.value = false
                    // Nothing to retry until another login replaces this one; leaving the screens ends the wait too.
                    api.principalEpoch.first { it != epoch }
                    failures = 0
                    statusState.value = LiveStatus.Connecting
                    continue
                }
                SessionFailure.Unsupported -> {
                    // No socket on this server at all: asking again changes nothing, so only the slow reads remain.
                    statusState.value = LiveStatus.Unavailable
                    fallbackState.value = true
                    awaitCancellation()
                }
                SessionFailure.AccessChanged -> if (accessChanges++ == 0) {
                    // The access under a valid login changed: a new ticket at once. A repeat falls through to backoff.
                    statusState.value = LiveStatus.Reconnecting
                    continue
                }
                else -> Unit
            }
            // A connection that never came up, or that dropped before it proved itself, is a failed connect.
            failures++
            if (failures >= config.fallbackAfterFailures) fallbackState.value = true
            statusState.value = LiveStatus.Reconnecting
            val ceiling = if (failures == 0) config.backoffBaseMillis
            else minOf(config.backoffMaxMillis, config.backoffBaseMillis shl (failures - 1).coerceAtMost(20))
            // Full jitter: anywhere from none to the ceiling. The network or the app returning cuts the wait short.
            val wait = (ceiling * jitter().coerceIn(0.0, 1.0)).toLong()
            withTimeoutOrNull(wait) { kicks.first { it != Kick.Restart } }
        }
    }

    /** One connection. Never returns: it ends by throwing why it ended. */
    private suspend fun attempt(onEstablished: () -> Unit, onStable: () -> Unit): Nothing = coroutineScope {
        val stable = launch(start = CoroutineStart.LAZY) {
            delay(config.stableMillis)
            onStable()
        }
        val control = Control { onEstablished(); stable.start() }
        launch {
            kicks.collect {
                when (it) {
                    Kick.Network -> throw NetworkChange()
                    Kick.Restart -> throw SessionTransportException(SessionFailure.Connection)
                    Kick.Foreground -> Unit
                }
            }
        }
        launch {
            activity.onStart { emit(Unit) }.collectLatest {
                delay(config.watchdogMillis)
                throw SessionTransportException(SessionFailure.Connection)
            }
        }
        offerState.value = LiveOffer()
        try { transport.connect(control) } finally { control.end() }
        throw SessionTransportException(SessionFailure.Connection)
    }

    private fun bump(channel: String) {
        ticks.getOrPut(channel) { MutableStateFlow(0L) }.update { it + 1 }
    }

    private inner class Control(private val onEstablished: () -> Unit) : LiveControl {
        @Volatile private var ended = false
        private var established = false
        /** Whatever a finished connection still says is dropped. */
        fun end() { ended = true }
        private val hydrated = HashSet<String>()
        private fun establish() {
            if (established) return
            established = true
            onEstablished()
        }
        override fun wanted(): Set<String> = wantedState.value
        override val wantedChanges: Flow<Set<String>> get() = wantedState
        override fun onSubscribed(accepted: Set<String>, rejected: Map<String, String>) {
            if (ended) return
            // A channel that left the subscription gets a fresh snapshot, and so a cue, if it comes back.
            synchronized(hydrated) { hydrated.retainAll(accepted) }
            offerState.update { LiveOffer(true, accepted, rejected, it.hydrated intersect accepted) }
            // With nothing accepted no snapshot follows; the answer itself is the proof the server took us.
            if (accepted.isEmpty()) establish()
        }
        override fun onSnapshot(channel: String) {
            if (ended) return
            // A new subscribe answers every channel again; only a channel not yet seen on this connection is news.
            // Connected first, so a screen woken by the cue already sees the socket as live.
            establish()
            if (synchronized(hydrated) { hydrated.add(channel) }) {
                offerState.update { it.copy(hydrated = it.hydrated + channel) }
                bump(channel)
            }
        }
        override fun onEvent(channel: String) {
            if (ended) return
            bump(channel)
            // The widgets on the home screen hear of it too, while the app is in front.
            LiveCues.emit(api.origin.issuer, channel)
        }
        override fun onActivity() { if (!ended) activity.tryEmit(Unit) }
    }
}
