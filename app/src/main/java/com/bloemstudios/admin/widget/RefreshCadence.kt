package com.bloemstudios.admin.widget

/**
 * Why a widget refresh was asked for, and how soon it may run. The 15-minute periodic job is the floor of freshness; these are
 * the moments in between where the data is likely to have moved, or where the person is about to look.
 *
 * [delayMillis] is a short wait that merges a burst of the same reason into one run. [minGapMillis] is the least time since
 * the last finished run for the reason to be honoured at all; a person's own tap is never held back.
 */
enum class RefreshReason(val delayMillis: Long, val minGapMillis: Long) {
    /** The Refresh button on a widget. */
    UserRefresh(delayMillis = 0, minGapMillis = 0),

    /** A widget was just placed or its server was chosen: it has nothing to show yet. */
    Placed(delayMillis = 0, minGapMillis = 0),

    /** The app saved something a widget shows (a request decided, a scan or a backup started, any accepted write). */
    AppSaved(delayMillis = 1_500, minGapMillis = 0),

    /** A push arrived for a server: something that server shows has probably changed. */
    Push(delayMillis = 3_000, minGapMillis = 0),

    /** The app went to the background: the widget should show what the person just saw. Coming and going is frequent, so once a minute at most. */
    Background(delayMillis = 2_000, minGapMillis = 60_000),

    /** The phone started or the app was updated: the system settles first. */
    Boot(delayMillis = 30_000, minGapMillis = 0),
}

object RefreshPolicy {
    /** True when [reason] should be honoured now, given when the last run finished (null: never). */
    fun allowed(reason: RefreshReason, nowMillis: Long, lastRunAtMillis: Long?): Boolean =
        reason.minGapMillis <= 0 || lastRunAtMillis == null || nowMillis - lastRunAtMillis >= reason.minGapMillis
}

/** A stream that was playing when a server was last read, reduced to what the follow-up schedule needs. */
data class ActiveStream(
    /** When the file should end if it keeps playing (epoch milliseconds), or null when it is paused or its length is unknown. */
    val endsAtMillis: Long?,
)

/**
 * The faster rhythm while something plays. The periodic job still runs every 15 minutes; while at least one stream is live a
 * one-shot follow-up is added: when the soonest stream should end (so the card goes away close to when it does), and
 * otherwise every [CEILING_MILLIS]. It never comes sooner than [FLOOR_MILLIS] after the run that scheduled it, so the
 * request rate is at most one per server per five minutes, however many streams play. Nothing is scheduled when nothing plays.
 */
object ActiveCadence {
    const val FLOOR_MILLIS = 5L * 60_000L
    const val CEILING_MILLIS = 10L * 60_000L

    /** A moment past the expected end, so the server has dropped the session by the time it is asked. */
    const val END_SLACK_MILLIS = 20_000L

    /** How long to wait before reading again, or null when nothing is playing. */
    fun nextDelayMillis(nowMillis: Long, active: List<ActiveStream>): Long? {
        if (active.isEmpty()) return null
        val soonestEnd = active.mapNotNull { it.endsAtMillis }.minOrNull()
        val wanted = if (soonestEnd == null) CEILING_MILLIS else soonestEnd - nowMillis + END_SLACK_MILLIS
        return wanted.coerceIn(FLOOR_MILLIS, CEILING_MILLIS)
    }

    /** The live streams in the last good Now playing reads, one entry each (a stream beyond the cached few has no known end). */
    fun streamsIn(slices: Collection<CachedSlice>): List<ActiveStream> = slices.filter { it.outcome == Outcome.Ok }
        .mapNotNull { slice -> slice.nowPlaying?.let { it to (slice.dataAt ?: slice.attemptedAt) } }
        .flatMap { (data, readAt) ->
            val known = data.streams.map { stream ->
                ActiveStream(endsAtMillis = if (stream.paused) null else stream.endsAt(readAt))
            }
            known + List((data.total - data.streams.size).coerceAtLeast(0)) { ActiveStream(null) }
        }
}
