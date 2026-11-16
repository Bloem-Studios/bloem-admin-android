package com.bloemstudios.admin.widget

import com.bloemstudios.admin.realtime.LiveChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * While the app is in front, its events socket reports changes. When one is on a channel that a placed widget shows, the app
 * reads that server again and redraws the widget, so the home screen follows what the person sees in the app. The widgets
 * themselves never open a socket, and nothing here runs in the background: the socket is closed then, and a cue that lands as
 * the app leaves is dropped (the background refresh covers it).
 *
 * At most one read per widget kind every [minGapMillis]: the first cue reads at once; cues inside the gap are merged into one
 * read when it ends (so the last change is never lost), naming every server that cued.
 */
class LiveWidgetFeed(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val foreground: () -> Boolean,
    private val placedKinds: () -> Set<WidgetKind>,
    /** The saved server at an address, or null when it is no longer saved. */
    private val serverIdFor: suspend (origin: String) -> String?,
    /** Reads [kind] for [serverIds] and redraws. */
    private val refresh: suspend (kind: WidgetKind, serverIds: Set<String>) -> Unit,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
) {
    private val lock = Any()
    private val lastRun = HashMap<WidgetKind, Long>()
    private val pending = HashMap<WidgetKind, MutableSet<String>>()
    private val loops = HashMap<WidgetKind, Job>()

    /** A change on [channel] from the server at [origin]. */
    fun onCue(origin: String, channel: String) {
        if (!foreground()) return
        val shown = placedKinds()
        for (kind in kindsFor(channel)) {
            if (kind !in shown) continue
            synchronized(lock) {
                pending.getOrPut(kind) { mutableSetOf() }.add(origin)
                if (loops[kind]?.isActive != true) loops[kind] = scope.launch { loop(kind) }
            }
        }
    }

    private suspend fun loop(kind: WidgetKind) {
        while (true) {
            val wait = synchronized(lock) { lastRun[kind]?.let { it + minGapMillis - clock() } ?: 0L }
            if (wait > 0) delay(wait)
            val origins = synchronized(lock) {
                val next = pending.remove(kind)
                if (next.isNullOrEmpty()) { loops.remove(kind); return }
                lastRun[kind] = clock()
                next
            }
            // The app may have gone to the background while waiting: its own background refresh covers that.
            if (!foreground()) continue
            val ids = origins.mapNotNull { serverIdFor(it) }.toSet()
            if (ids.isNotEmpty()) runCatching { refresh(kind, ids) }
        }
    }

    companion object {
        const val MIN_GAP_MILLIS = 10_000L

        /** The widget kinds a channel's change can alter: sessions are Now playing; scans, tasks and library jobs are Libraries. */
        fun kindsFor(channel: String): Set<WidgetKind> = when (channel) {
            LiveChannels.SESSIONS -> setOf(WidgetKind.NowPlaying)
            LiveChannels.SCANS, LiveChannels.TASKS, LiveChannels.JOBS -> setOf(WidgetKind.Libraries)
            else -> emptySet()
        }
    }
}
