package com.bloemstudios.admin.alerts

import kotlinx.serialization.Serializable

/** What one saved server's checks have shown so far. Kept between runs, never contains anything about the server's content. */
@Serializable
data class ReachabilityState(
    /** Checks in a row that got no answer. */
    val failures: Int = 0,
    /** When the first of those checks failed (epoch milliseconds). */
    val failingSince: Long? = null,
    /** A "can't reach" alert is showing for this outage. */
    val downNotified: Boolean = false,
)

enum class AlertEvent {
    None,
    /** Raise the "can't reach" alert. */
    Down,
    /** Replace it with one quiet "reachable again" note. */
    Back,
    /** Take any open alert away without a note: alerts were switched off. */
    Clear,
}

data class ReachabilityStep(val state: ReachabilityState, val event: AlertEvent)

object Reachability {
    /** A server must miss this many checks in a row before anyone is told. One miss is usually a network blip. */
    const val FAILURES_BEFORE_ALERT = 2

    fun next(state: ReachabilityState, reachable: Boolean, alertsOn: Boolean, nowMillis: Long): ReachabilityStep {
        if (reachable) {
            val event = when {
                !state.downNotified -> AlertEvent.None
                alertsOn -> AlertEvent.Back
                else -> AlertEvent.Clear
            }
            return ReachabilityStep(ReachabilityState(), event)
        }
        val failures = if (state.failures == Int.MAX_VALUE) Int.MAX_VALUE else state.failures + 1
        val since = state.failingSince ?: nowMillis
        val raise = alertsOn && !state.downNotified && failures >= FAILURES_BEFORE_ALERT
        val clear = !alertsOn && state.downNotified
        return ReachabilityStep(
            ReachabilityState(failures, since, downNotified = (state.downNotified && alertsOn) || raise),
            when {
                raise -> AlertEvent.Down
                clear -> AlertEvent.Clear
                else -> AlertEvent.None
            },
        )
    }
}
