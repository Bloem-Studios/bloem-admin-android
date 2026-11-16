package com.bloemstudios.admin.widget

import android.content.Context
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.ServerStatusStore
import com.bloemstudios.admin.alerts.SurfacePreferences
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.RequestAction
import com.bloemstudios.admin.network.api.actOnRequest
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStoreHolder
import com.bloemstudios.admin.servers.readSavedServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Opens the signed-in reads and the one write the widgets use. A seam: the real one uses the app's token store. */
interface WidgetSession : AutoCloseable {
    fun hasSession(server: AdminServer): Boolean
    fun source(server: AdminServer): WidgetSource
    suspend fun decide(server: AdminServer, requestId: String, approve: Boolean)
}

/** The app's own sign-in: the one [com.bloemstudios.admin.servers.ServerStore] the screens use, refreshed the same way. */
class StoreWidgetSession(private val context: Context) : WidgetSession {
    private var lease: ServerStoreHolder.Lease? = null

    private fun store() = (lease ?: ServerStoreHolder.acquire(context).also { lease = it }).store

    private fun client(server: AdminServer): ApiClient = store().client(server)

    override fun hasSession(server: AdminServer): Boolean = store().let { it.accessToken(server.id) != null || it.refreshToken(server.id) != null }

    override fun source(server: AdminServer): WidgetSource = ClientWidgetSource(client(server))

    override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) {
        client(server).actOnRequest(requestId, if (approve) RequestAction.APPROVE else RequestAction.DECLINE)
    }

    override fun close() {
        lease?.close()
        lease = null
    }
}

/**
 * Reads what the placed widgets need and keeps it in the [WidgetCache]. It runs inside the 15-minute server check (so one
 * schedule, one network constraint) and again when the user taps Refresh or the app saves something. A server is read only for
 * the kinds that a placed widget shows for it; a server that did not answer the health check is not asked again.
 */
class WidgetRefresher(
    private val servers: suspend () -> List<AdminServer>,
    private val surfaces: SurfacePreferences,
    private val statuses: ServerStatusStore,
    private val cache: WidgetCache,
    private val session: WidgetSession,
    private val clock: () -> Long = System::currentTimeMillis,
    private val perReadTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
    /** The widgets that exist. The app passes the system's list; the records in [surfaces] are only a note of the same. */
    private val placedWidgets: () -> List<PlacedWidget> = { surfaces.placedWidgets() },
) {
    /** What is wanted: for each saved server, the kinds some placed widget shows. */
    fun demand(saved: List<AdminServer>): Map<AdminServer, Set<WidgetKind>> {
        val placed = placedWidgets().filter { it.kind != WidgetKind.ServerStatus }
        val wanted = buildMap<String, MutableSet<WidgetKind>> {
            placed.forEach { widget ->
                val ids = widget.target?.let(::listOf) ?: saved.map { it.id }
                ids.forEach { getOrPut(it) { mutableSetOf() }.add(widget.kind) }
            }
        }
        return saved.filter { it.id in wanted }.associateWith { wanted.getValue(it.id) }
    }

    /**
     * One read of each server for what the placed widgets show: a single request per server for Now playing (the session list),
     * and for the other kinds only the reads that kind needs. [kinds] and [serverIds] narrow a run to the kinds or servers
     * that changed (the follow-up while something plays, a cue from the live socket); null means everything that is placed.
     */
    suspend fun run(kinds: Set<WidgetKind>? = null, serverIds: Set<String>? = null) {
        try {
            val saved = servers()
            cache.retain(saved.map { it.id }.toSet())
            val demand = demand(saved)
                .filterKeys { serverIds == null || it.id in serverIds }
                .mapValues { (_, wanted) -> if (kinds == null) wanted else wanted intersect kinds }
                .filterValues { it.isNotEmpty() }
            if (demand.isEmpty()) return
            coroutineScope {
                demand.map { (server, kinds) -> async { refreshServer(server, kinds) } }.awaitAll()
            }
        } finally {
            session.close()
        }
    }

    private suspend fun refreshServer(server: AdminServer, kinds: Set<WidgetKind>) {
        val now = clock()
        val reachable = statuses.records()[server.id]?.status?.reachable
        kinds.forEach { kind ->
            val previous = cache.slice(server.id, kind)
            val slice = when {
                reachable == false -> WidgetFetch.timedOut(previous, now)
                !session.hasSession(server) -> WidgetFetch.signInNeeded(now)
                else -> {
                    val source = session.source(server)
                    // One slow read must not hold up the other servers or the rest of the check.
                    withTimeoutOrNull(perReadTimeoutMillis) { WidgetFetch.read(kind, source, previous, now) }
                        ?: WidgetFetch.timedOut(previous, now)
                }
            }
            cache.put(server.id, kind, slice)
            // A fresh read of the requests clears an earlier "Couldn't approve".
            if (kind == WidgetKind.Requests && slice.outcome == Outcome.Ok) cache.setNotice(server.id, null)
        }
    }

    companion object {
        const val READ_TIMEOUT_MILLIS = 20_000L

        fun create(context: Context): WidgetRefresher {
            val app = context.applicationContext
            return WidgetRefresher(
                servers = { readSavedServers(app) },
                surfaces = AlertsRuntime.surfacePreferences(app),
                statuses = AlertsRuntime.statusStore(app),
                cache = WidgetCache.forContext(app),
                session = StoreWidgetSession(app),
                placedWidgets = { WidgetPlacement.placed(app) },
            )
        }
    }
}

/** What a tap on Approve or Decline came to. */
enum class DecisionResult { Done, Failed }

/** Approve or Decline from a widget: one call, then the cache is brought in line with what happened. */
class RequestDecision(
    private val servers: suspend () -> List<AdminServer>,
    private val cache: WidgetCache,
    private val session: WidgetSession,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(serverId: String, requestId: String, approve: Boolean): DecisionResult {
        val server = servers().firstOrNull { it.id == serverId } ?: return failed(serverId, approve)
        return try {
            if (!session.hasSession(server)) return failed(serverId, approve)
            session.decide(server, requestId, approve)
            cache.dropRequest(serverId, requestId)
            cache.setNotice(serverId, null)
            DecisionResult.Done
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed(serverId, approve)
        }
    }

    private fun failed(serverId: String, approve: Boolean): DecisionResult {
        cache.setNotice(serverId, ActionNotice(serverId, approve, clock()))
        return DecisionResult.Failed
    }
}
