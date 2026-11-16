package com.bloemstudios.admin.requests

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.getRequestCounts
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Asks the menu's count to be read again now. The Requests pages call it when their own counts change, and a push that
 * says a request arrived calls it too. A no-op where there is no menu.
 */
val LocalRequestCountsNudge = staticCompositionLocalOf<() -> Unit> { {} }

/** One server whose count is read. [client] may throw (no stored sign-in); that counts as a passing failure. */
class CountsTarget(val key: String, val client: () -> ApiClient)

/**
 * The server cannot take part in request administration at all: the service is not there, or this account may not
 * use it. Asking again changes nothing, so the web stops reading and so does this.
 */
val ApiError.endsRequestPolling: Boolean
    get() = code == "dependency_unavailable" || code == "permission_denied"

/** The server says request administration is not available (503 `dependency_unavailable`). */
val ApiError.isRequestAdminUnavailable: Boolean
    get() = code == "dependency_unavailable"

/**
 * The Needs approval count shown on the Requests menu item, from `GET /api/v2/admin/requests/counts`, summed over the
 * servers given (one server, or all of them). The events socket has no channel for requests, so there is no timer: the
 * count is read when [run] starts (the menu opens, or the app comes back to the front), and again on each [nudge]: the
 * Requests pages nudge it when they read their own counts (opening, pull to refresh, an action), and so will a push.
 * A server that answers `dependency_unavailable` or `permission_denied` is left alone from then on; a passing failure
 * keeps that server's last count, so the badge does not blink off on a weak connection. [run] returns once no server is
 * left to read, and otherwise waits for a nudge until it is cancelled.
 */
class RequestCountsPoller(
    /** Waits for the next nudge; tests replace it. */
    private val pause: (suspend () -> Unit)? = null,
) {
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /** Reads again now: the Requests page calls it after its own counts change, and a push for a request will. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    private suspend fun wait() {
        pause?.let { return it() }
        nudges.receive()
    }

    private val _needsApproval = MutableStateFlow<Int?>(null)

    /** The summed count, or null until a server has answered. */
    val needsApproval: StateFlow<Int?> = _needsApproval

    private val counts = HashMap<String, Int>()

    suspend fun run(targets: List<CountsTarget>) {
        counts.clear()
        _needsApproval.value = null
        val active = targets.toMutableList()
        while (readAll(active)) wait()
    }

    /** One read of every server still in [active]; false once none is left. */
    private suspend fun readAll(active: MutableList<CountsTarget>): Boolean {
        if (active.isEmpty()) return false
        val outcomes = coroutineScope {
            active.map { target -> async { target to read(target) } }.awaitAll()
        }
        for ((target, outcome) in outcomes) when (outcome) {
            is Read.Counts -> counts[target.key] = outcome.counts.needs_approval
            Read.Gone -> { active.remove(target); counts.remove(target.key) }
            Read.Passing -> Unit
        }
        _needsApproval.value = if (counts.isEmpty()) null else counts.values.sum()
        return active.isNotEmpty()
    }

    private sealed interface Read {
        data class Counts(val counts: RequestCounts) : Read
        data object Gone : Read
        data object Passing : Read
    }

    private suspend fun read(target: CountsTarget): Read = try {
        Read.Counts(target.client().getRequestCounts())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        if (e.endsRequestPolling) Read.Gone else Read.Passing
    } catch (_: Exception) {
        Read.Passing
    }
}
