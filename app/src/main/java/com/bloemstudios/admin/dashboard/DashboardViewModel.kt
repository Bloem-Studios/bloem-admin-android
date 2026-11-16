package com.bloemstudios.admin.dashboard

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.getAdminStats
import com.bloemstudios.admin.network.api.getBuildInfo
import com.bloemstudios.admin.network.api.getPlaybackActivity
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.api.getSystemResources
import com.bloemstudios.admin.network.api.getTimeseries
import com.bloemstudios.admin.network.api.getTopActivity
import com.bloemstudios.admin.network.api.listLibraries
import com.bloemstudios.admin.network.api.listNodes
import com.bloemstudios.admin.network.api.listPlaybackHistory
import com.bloemstudios.admin.network.api.listUsersForDashboard
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

// Dashboard sections publish independently; one failed or slow endpoint must not hide the others.
class DashboardViewModel : ViewModel() {
    private val _stats = MutableStateFlow<AdminStats?>(null)
    val stats: StateFlow<AdminStats?> = _stats
    private val _hostStats = MutableStateFlow<SystemResources?>(null)
    val hostStats: StateFlow<SystemResources?> = _hostStats
    // True once the server said it has no host sample to show; the screen hides the resources line.
    private val _hostUnavailable = MutableStateFlow(false)
    val hostUnavailable: StateFlow<Boolean> = _hostUnavailable
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage
    private val _hostError = MutableStateFlow<ErrorText?>(null)
    val hostError: StateFlow<ErrorText?> = _hostError
    private val _insights = MutableStateFlow(DashboardInsights())
    /** Health, nodes, playback activity, egress, top lists, libraries, users and recent activity. */
    val insights: StateFlow<DashboardInsights> = _insights
    private val _ranges = MutableStateFlow(ChartRanges())
    /** The window each trend chart shows. Kept here, not in the screen, so it survives rotation and is per server. */
    val ranges: StateFlow<ChartRanges> = _ranges
    private val latestRequest = LatestRequestGate()
    /** Begins whenever the samples are cleared: a chart read that started before then must not publish into the new samples. */
    private val sampleEpoch = LatestRequestGate()
    private val _sampledAt = MutableStateFlow<Instant?>(null)
    val sampledAt: StateFlow<Instant?> = _sampledAt
    private var boundClient: ApiClient? = null
    private val invalidation: () -> Unit = { clearSamples() }
    private var slowLoadedAt: Instant? = null
    /** The clock the slow tier is paced against; tests replace it. */
    internal var clock: () -> Instant = Instant::now

    private fun clearSamples() {
        latestRequest.begin()
        sampleEpoch.begin()
        _stats.value = null; _hostStats.value = null; _hostUnavailable.value = false
        _errorMessage.value = null; _hostError.value = null; _sampledAt.value = null
        _insights.value = DashboardInsights(); slowLoadedAt = null
    }

    fun close() {
        boundClient?.let { old -> synchronized(old.sessionAuthorityLock) { old.sessionInvalidations -= invalidation } }
        boundClient = null
        clearSamples()
    }

    override fun onCleared() { close() }

    /** Show [range] on one chart. The read for it is [loadCharts]; the screen starts it when the range changes. */
    fun selectRange(kind: ChartKind, range: ChartRange) { _ranges.update { it.with(kind, range) } }

    /** The dashboard's one range control: every trend chart moves to [range] together. */
    fun selectRange(range: ChartRange) { _ranges.update { it.all(range) } }

    fun reportUnavailable() {
        _errorMessage.value = ErrorText.Res(R.string.error_could_not_load_dashboard)
        _hostError.value = ErrorText.Res(R.string.status_health_unavailable)
    }

    private fun bind(client: ApiClient) {
        if (boundClient === client) return
        close()
        synchronized(client.sessionAuthorityLock) {
            boundClient = client
            client.sessionInvalidations += invalidation
        }
    }

    /** What changes slowly (version, 24h egress, 7-day leaders, libraries, accounts) is read every few minutes, not every poll. */
    private fun slowTierDue(forceRefresh: Boolean): Boolean {
        val last = slowLoadedAt
        val current = _insights.value
        return forceRefresh || last == null || Duration.between(last, clock()) >= SLOW_TIER_INTERVAL ||
            listOf(current.build.error, current.egress24h.error, current.top.error, current.libraries.error, current.users.error).any { it != null }
    }

    /**
     * One independently published section: the value on success, or the last good value beside the error.
     * [publish] decides whether the result may still be shown.
     */
    private fun <T> sectionLoad(
        scope: kotlinx.coroutines.CoroutineScope,
        publish: (() -> Unit) -> Unit,
        read: (DashboardInsights) -> SectionState<T>,
        write: (DashboardInsights, SectionState<T>) -> DashboardInsights,
        fetch: suspend () -> T,
    ): Deferred<Unit> = scope.async {
        try {
            val value = fetch()
            publish { _insights.update { write(it, SectionState(value)) } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val error = if (e is ApiError) e.toErrorText(R.string.dashboard_section_unavailable) else ErrorText.Res(R.string.dashboard_section_unavailable)
            // The last good value stays beside the failure, so a flaky refresh does not blank a section.
            publish { _insights.update { write(it, SectionState(read(it).value, error)) } }
        }
    }

    /**
     * The reads behind the trend charts for windows the regular poll does not already make. A day is the 24-hour
     * reads every poll makes and an hour is the "egress now" read; a week or a month is read once when chosen,
     * then with the slow tier. [all] re-reads every shown window; otherwise only those not yet loaded or that failed.
     */
    private fun chartLoads(
        scope: kotlinx.coroutines.CoroutineScope, client: ApiClient, publish: (() -> Unit) -> Unit, forceRefresh: Boolean, all: Boolean,
    ): List<Deferred<Unit>> {
        val shown = _ranges.value
        val current = _insights.value
        val loads = mutableListOf<Deferred<Unit>>()
        // An hour of playback changes within a poll or two, so unlike the wider windows it is read every time.
        if (shown.playback != ChartRange.Day && needsRead(all || shown.playback == ChartRange.Hour, current.rangedActivity[shown.playback])) {
            val range = shown.playback
            loads += sectionLoad(scope, publish, { it.rangedActivity[range] ?: SectionState() },
                { i, s -> i.copy(rangedActivity = i.rangedActivity + (range to s)) }) { client.getPlaybackActivity(range.hours, forceRefresh) }
        }
        setOf(shown.egress, shown.streams).filter { it != ChartRange.Day && it != ChartRange.Hour }.forEach { range ->
            if (needsRead(all, current.rangedSeries[range])) {
                loads += sectionLoad(scope, publish, { it.rangedSeries[range] ?: SectionState() },
                    { i, s -> i.copy(rangedSeries = i.rangedSeries + (range to s)) }) { client.getTimeseries(range.hours, forceRefresh) }
            }
        }
        return loads
    }

    /** A shown window is read when asked to re-read everything, when it has never been read, and again after it failed. */
    private fun needsRead(all: Boolean, state: SectionState<*>?): Boolean = all || state == null || state.error != null

    /**
     * Read what a chart needs for the window just chosen, without re-reading the rest of the dashboard. Does nothing
     * before the first [load] has bound this model to the client; that load reads the chosen windows itself.
     */
    suspend fun loadCharts(client: ApiClient, forceRefresh: Boolean = false) = coroutineScope {
        if (boundClient !== client) return@coroutineScope
        val epoch = client.currentPrincipalEpoch
        val sample = sampleEpoch.current()
        fun publish(block: () -> Unit) = synchronized(client.sessionAuthorityLock) {
            if (boundClient === client && sampleEpoch.isCurrent(sample) && client.currentPrincipalEpoch == epoch && !client.isClosed.value &&
                client.liveSessions.state.value != com.bloemstudios.admin.realtime.RealtimeState.SignInRequired) block()
        }
        awaitAll(*chartLoads(this, client, ::publish, forceRefresh, all = false).toTypedArray())
        Unit
    }

    suspend fun load(client: ApiClient, forceRefresh: Boolean = false) = coroutineScope {
        bind(client)
        val epoch = client.currentPrincipalEpoch
        val generation = latestRequest.begin()
        fun publish(block: () -> Unit) = synchronized(client.sessionAuthorityLock) {
            if (boundClient === client && latestRequest.isCurrent(generation) &&
                client.currentPrincipalEpoch == epoch && !client.isClosed.value &&
                client.liveSessions.state.value != com.bloemstudios.admin.realtime.RealtimeState.SignInRequired) block()
        }
        val statsLoad = async {
            try {
                val result = client.getAdminStats(forceRefresh)
                publish {
                    _stats.value = result
                    _sampledAt.value = Instant.now()
                    _errorMessage.value = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish { _errorMessage.value = if (e is ApiError) e.toErrorText(R.string.error_could_not_load_dashboard) else ErrorText.Res(R.string.error_could_not_load_dashboard) }
            }
        }
        val hostLoad = async {
            try {
                val result = client.getSystemResources()
                val usable = result.available && result.system != null
                publish {
                    _hostStats.value = result.takeIf { usable }
                    _hostUnavailable.value = !usable
                    _hostError.value = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish {
                    _hostStats.value = null
                    _hostUnavailable.value = false
                    _hostError.value = if (e is ApiError) e.toErrorText(R.string.status_health_unavailable) else ErrorText.Res(R.string.status_health_unavailable)
                }
            }
        }
        fun <T> section(
            read: (DashboardInsights) -> SectionState<T>,
            write: (DashboardInsights, SectionState<T>) -> DashboardInsights,
            fetch: suspend () -> T,
        ): Deferred<Unit> = sectionLoad(this, ::publish, read, write, fetch)
        val loads = mutableListOf<Deferred<Unit>>(statsLoad, hostLoad)
        loads += section({ it.status }, { i, s -> i.copy(status = s) }) { client.getServerStatus() }
        loads += section({ it.nodes }, { i, s -> i.copy(nodes = s) }) { client.listNodes() }
        loads += section({ it.activity }, { i, s -> i.copy(activity = s) }) { client.getPlaybackActivity(ACTIVITY_HOURS, forceRefresh) }
        loads += section({ it.egressNow }, { i, s -> i.copy(egressNow = s) }) { client.getTimeseries(1, forceRefresh) }
        loads += section({ it.recent }, { i, s -> i.copy(recent = s) }) { client.listPlaybackHistory(null, null, RECENT_LIMIT).items }
        val slow = slowTierDue(forceRefresh)
        loads += chartLoads(this, client, ::publish, forceRefresh, all = slow)
        if (slow) {
            slowLoadedAt = clock()
            loads += section({ it.build }, { i, s -> i.copy(build = s) }) { client.getBuildInfo() }
            loads += section({ it.egress24h }, { i, s -> i.copy(egress24h = s) }) { client.getTimeseries(ACTIVITY_HOURS, forceRefresh) }
            loads += section({ it.top }, { i, s -> i.copy(top = s) }) { client.getTopActivity(TOP_DAYS, TOP_LIMIT, forceRefresh) }
            loads += section({ it.libraries }, { i, s -> i.copy(libraries = s) }) { client.listLibraries() }
            loads += section({ it.users }, { i, s -> i.copy(users = s) }) { client.listUsersForDashboard() }
        }
        awaitAll(*loads.toTypedArray())
        Unit
    }

    private companion object {
        const val ACTIVITY_HOURS = 24
        const val TOP_DAYS = 7
        const val TOP_LIMIT = 5
        const val RECENT_LIMIT = 5
        val SLOW_TIER_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
