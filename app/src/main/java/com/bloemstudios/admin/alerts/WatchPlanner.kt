package com.bloemstudios.admin.alerts

/**
 * What one background run has to do. Built from the servers that are saved right now, so a removed server drops out
 * of every part of it.
 */
data class WatchPlan(
    /** Servers that get a health check. */
    val checked: Set<String>,
    /** Servers that may raise alerts (the master switch is on and the server is switched on). */
    val alerted: Set<String>,
    /** Servers whose stream and request numbers are read, because a widget or the tile shows them. */
    val withCounts: Set<String>,
) {
    val isEmpty: Boolean get() = checked.isEmpty()
}

object WatchPlanner {
    /**
     * [widgetTargets] has one entry per placed widget: a saved server id, or null for All servers.
     * [alerted] is [AlertPreferences.alertedServerIds] (already empty while the master switch is off).
     */
    fun plan(
        savedIds: Set<String>,
        alerted: Set<String>,
        widgetTargets: Collection<String?>,
        tileAdded: Boolean,
    ): WatchPlan {
        val alertedSaved = alerted.intersect(savedIds)
        val shown = buildSet {
            widgetTargets.forEach { target -> if (target == null) addAll(savedIds) else if (target in savedIds) add(target) }
            if (tileAdded) addAll(savedIds)
        }
        return WatchPlan(checked = alertedSaved + shown, alerted = alertedSaved, withCounts = shown)
    }

    /** True when something could need the periodic check; the run itself still checks which saved servers that covers. */
    fun wantsBackgroundWork(alertedServerIds: Set<String>, widgetCount: Int, tileAdded: Boolean): Boolean =
        alertedServerIds.isNotEmpty() || widgetCount > 0 || tileAdded
}
