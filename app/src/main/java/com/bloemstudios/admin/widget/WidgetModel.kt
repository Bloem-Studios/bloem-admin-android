package com.bloemstudios.admin.widget

import com.bloemstudios.admin.alerts.CountsStatus
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.servers.AdminServer

enum class Reach { Reachable, NotReachable, NotChecked }

sealed interface WidgetCounts {
    data class Known(val streams: Int, val requests: Int) : WidgetCounts
    /** No session for this server: the widget says so instead of showing numbers. */
    data object SignInNeeded : WidgetCounts
    data object Unavailable : WidgetCounts
    /** Nothing to show: not checked yet, or the server is not reachable. */
    data object Hidden : WidgetCounts
}

/**
 * Everything a widget draws, and nothing else: a server name, whether it answers, and two counts. No address, token,
 * user, title or time of day can reach the home screen through this type.
 */
sealed interface WidgetModel {
    data class Single(val serverName: String, val reach: Reach, val counts: WidgetCounts) : WidgetModel {
        /** The tally dot: at least one stream is live right now. */
        val live: Boolean get() = counts is WidgetCounts.Known && counts.streams > 0
    }

    data class All(
        val servers: Int,
        val reachable: Int,
        val notReachable: Int,
        val streams: Int,
        val requests: Int,
        /** How many servers the totals cover. */
        val countedServers: Int,
    ) : WidgetModel {
        val live: Boolean get() = streams > 0
    }

    /** The server this widget showed was removed from the app. */
    data object ServerRemoved : WidgetModel

    /** All servers, with none saved. */
    data object NoServers : WidgetModel
}

object WidgetModels {
    /** [target] is a saved server id, or null for All servers. */
    fun map(target: String?, saved: List<AdminServer>, records: Map<String, ServerRecord>): WidgetModel {
        if (target != null) {
            val server = saved.firstOrNull { it.id == target } ?: return WidgetModel.ServerRemoved
            val status = records[server.id]?.status
            val reach = when (status?.reachable) {
                true -> Reach.Reachable
                false -> Reach.NotReachable
                null -> Reach.NotChecked
            }
            return WidgetModel.Single(server.displayName, reach, countsFor(status?.reachable, status?.counts, status?.streams, status?.requests))
        }
        if (saved.isEmpty()) return WidgetModel.NoServers
        val statuses = saved.map { records[it.id]?.status }
        val counted = statuses.filter { it?.reachable == true && it.counts == CountsStatus.Known }
        return WidgetModel.All(
            servers = saved.size,
            reachable = statuses.count { it?.reachable == true },
            notReachable = statuses.count { it?.reachable == false },
            streams = counted.sumOf { it?.streams ?: 0 },
            requests = counted.sumOf { it?.requests ?: 0 },
            countedServers = counted.size,
        )
    }

    private fun countsFor(reachable: Boolean?, counts: CountsStatus?, streams: Int?, requests: Int?): WidgetCounts = when {
        reachable != true -> WidgetCounts.Hidden
        counts == CountsStatus.Known && streams != null && requests != null -> WidgetCounts.Known(streams, requests)
        counts == CountsStatus.SignInNeeded -> WidgetCounts.SignInNeeded
        counts == CountsStatus.Unavailable -> WidgetCounts.Unavailable
        else -> WidgetCounts.Hidden
    }
}

/** What the Quick Settings tile shows. */
sealed interface TileModel {
    data class Streams(val count: Int) : TileModel
    /** Requests waiting for approval. */
    data class Waiting(val count: Int) : TileModel
    data object NoData : TileModel
}

object TileModels {
    /** Three missed runs of the 15-minute check: older numbers are not shown as current. */
    const val STALE_AFTER_MILLIS = 45L * 60_000L

    fun map(records: Map<String, ServerRecord>, nowMillis: Long): TileModel {
        val fresh = records.values.map { it.status }.filter {
            it.reachable == true && it.counts == CountsStatus.Known &&
                (it.checkedAt ?: Long.MIN_VALUE) >= nowMillis - STALE_AFTER_MILLIS
        }
        return if (fresh.isEmpty()) TileModel.NoData else TileModel.Streams(fresh.sumOf { it.streams ?: 0 })
    }

    /** Requests waiting, summed over the servers that answered recently. */
    fun mapRequests(records: Map<String, ServerRecord>, nowMillis: Long): TileModel {
        val fresh = records.values.map { it.status }.filter {
            it.reachable == true && it.counts == CountsStatus.Known &&
                (it.checkedAt ?: Long.MIN_VALUE) >= nowMillis - STALE_AFTER_MILLIS
        }
        return if (fresh.isEmpty()) TileModel.NoData else TileModel.Waiting(fresh.sumOf { it.requests ?: 0 })
    }
}
