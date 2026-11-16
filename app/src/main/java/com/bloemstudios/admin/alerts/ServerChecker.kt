package com.bloemstudios.admin.alerts

import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Asks one server's public health route whether it answers. Takes an address and nothing else, so it cannot send a credential. */
fun interface HealthProbe {
    suspend fun isReachable(baseUrl: String): Boolean
}

sealed interface CountsResult {
    data class Known(val streams: Int, val requests: Int) : CountsResult
    data object SignInNeeded : CountsResult
    data object Unavailable : CountsResult
}

/** Reads the numbers a widget or the tile shows. Needs the stored session, so it is separate from the health probe. */
fun interface CountsSource {
    suspend fun fetch(server: AdminServer): CountsResult
}

/** Where alerts go: the notification shade. Implemented by [AlertNotifier]; faked in tests. */
interface AlertSink {
    fun down(server: AdminServer, failingSince: Long?, failures: Int)
    fun back(server: AdminServer)
    fun clear(serverId: String)
}

/**
 * One background run: check the servers the admin asked to watch, record the answers for the widget and the tile,
 * and raise or clear alerts. Returns false when nothing needs watching any more, so the caller can stop the schedule.
 */
class ServerChecker(
    private val servers: suspend () -> List<AdminServer>,
    private val alerts: AlertPreferences,
    private val surfaces: SurfacePreferences,
    private val statuses: ServerStatusStore,
    private val probe: HealthProbe,
    private val counts: CountsSource,
    private val sink: AlertSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun runOnce(): Boolean {
        val saved = servers()
        val savedIds = saved.map { it.id }.toSet()

        // A removed server stops being watched, forgotten and alerted about.
        alerts.retainServers(savedIds)
        statuses.retain(savedIds).forEach(sink::clear)

        val plan = WatchPlanner.plan(
            savedIds = savedIds,
            alerted = alerts.alertedServerIds(savedIds),
            // Only Server status draws reachability and the counts; the other widgets are read by their own job.
            widgetTargets = surfaces.placedWidgets().filter { it.kind == com.bloemstudios.admin.widget.WidgetKind.ServerStatus }.map { it.target },
            tileAdded = surfaces.anyTile,
        )

        // An alert left open for a server that is no longer watched is taken down.
        statuses.records().forEach { (id, record) ->
            if (record.reach.downNotified && id !in plan.checked) {
                sink.clear(id)
                statuses.put(id, record.copy(reach = record.reach.copy(downNotified = false)))
            }
        }
        if (plan.isEmpty) return false

        coroutineScope {
            saved.filter { it.id in plan.checked }.map { server -> async { check(server, plan) } }.awaitAll()
        }
        return true
    }

    private suspend fun check(server: AdminServer, plan: WatchPlan) {
        val reachable = try {
            probe.isReachable(server.baseUrl)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        val now = clock()
        val previous = statuses.records()[server.id] ?: ServerRecord()
        val step = Reachability.next(previous.reach, reachable, alertsOn = server.id in plan.alerted, nowMillis = now)
        when (step.event) {
            AlertEvent.Down -> sink.down(server, step.state.failingSince, step.state.failures)
            AlertEvent.Back -> sink.back(server)
            AlertEvent.Clear -> sink.clear(server.id)
            AlertEvent.None -> Unit
        }
        val answer = if (reachable && server.id in plan.withCounts) readCounts(server) else null
        val status = when {
            answer is CountsResult.Known -> ServerStatus(true, now, CountsStatus.Known, answer.streams, answer.requests)
            answer is CountsResult.SignInNeeded -> ServerStatus(true, now, CountsStatus.SignInNeeded)
            answer is CountsResult.Unavailable -> ServerStatus(true, now, CountsStatus.Unavailable)
            else -> ServerStatus(reachable, now)
        }
        statuses.put(server.id, ServerRecord(status, step.state))
    }

    private suspend fun readCounts(server: AdminServer): CountsResult = try {
        counts.fetch(server)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        CountsResult.Unavailable
    }
}
