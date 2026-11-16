package com.bloemstudios.admin.realtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private const val COALESCE_MILLIS = 150L

/** A scan can send a change several times a second; reading more than once in this long adds nothing the eye can see. */
const val DEFAULT_MIN_GAP_MILLIS = 2_000L

/**
 * Keeps a screen's data current from the events sockets of [connections] (one per server on an all-servers page)
 * instead of a timer. Call it from the screen's foreground block; it runs until that block is cancelled, which is when
 * the screen leaves the foreground, and then releases every socket it held.
 *
 * - [refresh] runs once now (the open, unless [readOnOpen] is false because the caller has just read), then once for each cue on any of [channels] from any connection: the first
 *   snapshot of every connection (so a reconnect catches up on what it missed) and each change event. Cues that arrive
 *   while a read runs merge into one more read, and reads are at least [minGapMillis] apart.
 * - Only while a socket cannot be had (see [LiveConnection.fallback]), or a server does not offer one of the channels,
 *   [refresh] also runs every [LiveConnection.fallbackPollMillis]. While the sockets are up there is no timer.
 *   A screen that read on open and on pull before it had its channel passes [pollWhenNotOffered] = false: while its socket
 *   works but the server lacks the channel it stays exactly as it was (no timer). With no socket it falls back like the rest.
 */
suspend fun watchLive(
    connections: List<LiveConnection>,
    channels: Set<String>,
    minGapMillis: Long = DEFAULT_MIN_GAP_MILLIS,
    readOnOpen: Boolean = true,
    pollWhenNotOffered: Boolean = true,
    refresh: suspend () -> Unit,
): Unit = coroutineScope {
    val trigger = Channel<Unit>(Channel.CONFLATED)
    for (connection in connections) {
        // A counter only goes up and a collector always sees its latest value, so a cue that lands while a read runs is
        // never lost. The value at the start is the baseline: the read that opens the screen covers everything before it.
        for (channel in channels) {
            launch(start = CoroutineStart.UNDISPATCHED) { connection.signal(channel).drop(1).collect { trigger.trySend(Unit) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { connection.retain(channels) }
    }
    if (connections.isNotEmpty()) launch {
        val fallbackMillis = connections.maxOf { it.fallbackPollMillis }
        val slow = connections.map { connection ->
            combine(connection.fallback, connection.offer) { fallback, offer ->
                fallback || (pollWhenNotOffered && channels.any { it in offer.rejected })
            }
        }
        combine(slow) { flags -> flags.any { it } }
            .distinctUntilChanged()
            .collectLatest { on ->
                while (on) {
                    delay(fallbackMillis)
                    trigger.trySend(Unit)
                }
            }
    }
    var opening = readOnOpen
    if (readOnOpen) trigger.trySend(Unit)
    // A connection answers each channel with its own snapshot a few milliseconds apart: wait a moment so they are one read.
    val coalesceMillis = minOf(COALESCE_MILLIS, minGapMillis)
    while (true) {
        trigger.receive()
        if (!opening) delay(coalesceMillis)
        opening = false
        // Cues that came in while waiting are covered by the read that is about to start.
        trigger.tryReceive()
        try { refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the screen shows its own error; the next cue reads again */ }
        delay(minGapMillis)
    }
}

suspend fun LiveConnection.watch(
    channels: Set<String>,
    minGapMillis: Long = DEFAULT_MIN_GAP_MILLIS,
    readOnOpen: Boolean = true,
    pollWhenNotOffered: Boolean = true,
    refresh: suspend () -> Unit,
) = watchLive(listOf(this), channels, minGapMillis, readOnOpen, pollWhenNotOffered, refresh)
