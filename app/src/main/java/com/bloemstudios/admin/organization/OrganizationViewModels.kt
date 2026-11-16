package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getOrganizationOverview
import com.bloemstudios.admin.network.api.getOrganizationPerson
import com.bloemstudios.admin.network.api.listOrganizationActivity
import com.bloemstudios.admin.network.api.listOrganizationCohorts
import com.bloemstudios.admin.network.api.listOrganizationLibraries
import com.bloemstudios.admin.network.api.getOrganizationPolicyDecision
import com.bloemstudios.admin.network.api.listOrganizationPolicyDecisions
import com.bloemstudios.admin.network.api.deleteOrganizationEntitlement
import com.bloemstudios.admin.network.api.setOrganizationEntitlementStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/*
 * One ViewModel per organization page. Each takes the [OrganizationSession] on every call (never
 * stores it), keeps its state in a StateFlow, and turns failures into ErrorText through
 * [failureText]. Loads are guarded by [LatestRequestGate] so a slow answer never overwrites a newer one.
 */

// ---- Overview ---------------------------------------------------------------------------------------

data class OverviewState(
    val loading: Boolean = true,
    val summary: OrganizationSummary? = null,
    val error: ErrorText? = null,
    /** The owner's display name, else email, read from People. Null when there is no owner or the person could not be read. */
    val ownerName: String? = null,
    /** True once the owner has been looked for, so "name unavailable" is not shown while the page is still loading. */
    val ownerResolved: Boolean = false,
)

class OverviewViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.summary == null, error = null) }
        val outcome = session.attempt(R.string.organization_error_overview) { session.client.getOrganizationOverview() }
        if (!gate.isCurrent(request)) return
        when (outcome) {
            is Outcome.Failed -> _state.update { it.copy(loading = false, error = outcome.message) }
            is Outcome.Ok -> {
                // The owner is an account number to the overview; People knows the person. A person who cannot be read
                // is left unnamed: the page says "name unavailable", never the number.
                val owner = outcome.value.owner_account_id
                val ownerName = owner?.let { id ->
                    (session.attempt(R.string.organization_error_overview) { session.client.getOrganizationPerson(id) } as? Outcome.Ok)?.value?.label?.ifBlank { null }
                }
                if (!gate.isCurrent(request)) return
                _state.value = OverviewState(loading = false, summary = outcome.value, ownerName = ownerName, ownerResolved = true)
            }
        }
    }
}

// ---- Libraries & Entitlements -----------------------------------------------------------------------

data class LibrariesState(
    val loading: Boolean = true,
    val libraries: List<OrganizationLibrary> = emptyList(),
    val pendingFolderIds: Set<Long> = emptySet(),
    val error: ErrorText? = null,
    /** Set when a change was refused because the organization moved on; the list has been reloaded. */
    val notice: ErrorText? = null,
)

class LibrariesViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(LibrariesState())
    val state: StateFlow<LibrariesState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.libraries.isEmpty(), error = null) }
        val outcome = session.attempt(R.string.organization_error_libraries) { session.client.listOrganizationLibraries() }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(loading = false, libraries = outcome.value)
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message)
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    /** Pauses (`suspended`) or restores (`active`) a library's entitlement. */
    suspend fun setStatus(session: OrganizationSession, library: OrganizationLibrary, status: String) {
        val entitlement = library.entitlement ?: return
        if (library.isOwned) return
        run(session, library, if (status == "active") R.string.organization_notice_library_restored else R.string.organization_notice_library_paused) { client ->
            val updated = client.setOrganizationEntitlementStatus(library.folder_id, status, entitlement.security_revision)
            _state.update { state ->
                state.copy(libraries = state.libraries.map { if (it.folder_id == library.folder_id) it.copy(entitlement = updated) else it })
            }
        }
    }

    /**
     * Gives a shared library back for good. The web offers it for a shared library whose entitlement is not
     * already withdrawn; only a platform administrator can share it again. The organization's own libraries have
     * no entitlement and are never offered.
     */
    suspend fun giveBack(session: OrganizationSession, library: OrganizationLibrary) {
        val entitlement = library.entitlement ?: return
        if (library.isOwned || entitlement.status == "revoked") return
        val done = run(session, library, R.string.organization_notice_library_given_back) { client ->
            client.deleteOrganizationEntitlement(library.folder_id, entitlement.security_revision)
        }
        // The server keeps the row, now withdrawn; read it back rather than guess.
        if (done) load(session)
    }

    private suspend fun run(
        session: OrganizationSession,
        library: OrganizationLibrary,
        success: Int,
        block: suspend (com.bloemstudios.admin.network.ApiClient) -> Unit,
    ): Boolean {
        if (library.folder_id in _state.value.pendingFolderIds) return false
        _state.update { it.copy(pendingFolderIds = it.pendingFolderIds + library.folder_id, error = null, notice = null) }
        val outcome = session.attempt(R.string.organization_error_library_change) { session.write { client, _ -> block(client) } }
        _state.update { it.copy(pendingFolderIds = it.pendingFolderIds - library.folder_id) }
        return when (outcome) {
            is Outcome.Ok -> {
                _state.update { it.copy(notice = ErrorText.Res(success)) }
                true
            }
            is Outcome.Failed -> {
                load(session)
                _state.update { it.copy(error = outcome.message) }
                false
            }
        }
    }
}

// ---- Activity & Audit -------------------------------------------------------------------------------

data class OrganizationActivityState(
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val events: List<OrganizationAuditEvent> = emptyList(),
    val nextCursor: String? = null,
    val error: ErrorText? = null,
)

class OrganizationActivityViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(OrganizationActivityState())
    val state: StateFlow<OrganizationActivityState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.events.isEmpty(), loadingOlder = false, error = null) }
        val outcome = session.attempt(R.string.organization_error_activity) { session.client.listOrganizationActivity(null) }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> OrganizationActivityState(loading = false, events = outcome.value.events, nextCursor = outcome.value.next_cursor?.ifBlank { null })
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message)
            }
        }
    }

    suspend fun loadOlder(session: OrganizationSession) {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingOlder) return
        val request = gate.current()
        _state.update { it.copy(loadingOlder = true, error = null) }
        val outcome = session.attempt(R.string.organization_error_activity) { session.client.listOrganizationActivity(cursor) }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(
                    loadingOlder = false,
                    events = it.events + outcome.value.events.filter { event -> it.events.none { seen -> seen.id == event.id && seen.source == event.source } },
                    nextCursor = outcome.value.next_cursor?.ifBlank { null },
                )
                is Outcome.Failed -> it.copy(loadingOlder = false, error = outcome.message)
            }
        }
    }
}

/** The web's own wording for an action code; unknown codes read as sentence-cased words. */
fun auditActionLabelRes(action: String): Int? = when (action) {
    "organization.created" -> R.string.organization_action_organization_created
    "organization.renamed" -> R.string.organization_action_organization_renamed
    "organization.slug_changed" -> R.string.organization_action_organization_slug_changed
    "organization.suspended" -> R.string.organization_action_organization_suspended
    "organization.reactivated" -> R.string.organization_action_organization_reactivated
    "organization.ownership_transferred" -> R.string.organization_action_ownership_transferred
    "organization.entitlement_applied" -> R.string.organization_action_plan_applied
    "membership.created" -> R.string.organization_action_member_added
    "membership.role_changed" -> R.string.organization_action_role_changed
    "membership.status_changed" -> R.string.organization_action_member_status_changed
    "entitlement.policy_assignment" -> R.string.organization_action_plan_assigned
    "people.bulk_job_created" -> R.string.organization_action_bulk_started
    "people.bulk_policy_job_created" -> R.string.organization_action_bulk_policy_started
    "people.bulk_job_completed" -> R.string.organization_action_bulk_finished
    "people.bulk_job_failed" -> R.string.organization_action_bulk_failed
    "people.bulk_job_cancelled" -> R.string.organization_action_bulk_stopped
    "people.bulk_group_assigned" -> R.string.organization_action_bulk_group
    "people.bulk_policy_assigned" -> R.string.organization_action_bulk_policy_assigned
    "people.bulk_membership_suspended" -> R.string.organization_action_bulk_suspended
    "people.bulk_membership_reactivated" -> R.string.organization_action_bulk_reactivated
    "people.bulk_record_skipped" -> R.string.organization_action_bulk_skipped
    "people.bulk_record_failed" -> R.string.organization_action_bulk_record_failed
    "people.membership_updated" -> R.string.organization_action_membership_changed
    "people.profile_group_updated" -> R.string.organization_action_profile_group
    else -> null
}

/** Unknown action codes: `people.thing_done` becomes "People thing done". */
fun auditActionFallback(action: String): String {
    val words = action.replace(Regex("[._]+"), " ").trim()
    return if (words.isEmpty()) action else words.replaceFirstChar { it.uppercase() }
}

fun auditOutcomeLabelRes(outcome: String): Int? = when (outcome) {
    "partial_failure" -> R.string.organization_outcome_partial
    "conflict" -> R.string.organization_outcome_conflict
    "failed", "failure" -> R.string.organization_outcome_failed
    "skipped" -> R.string.organization_outcome_skipped
    "denied" -> R.string.organization_outcome_denied
    else -> null
}

val OrganizationAuditEvent.isSuccess: Boolean get() = outcome == "success" || outcome == "succeeded"

// ---- Policy cohorts (read-only) ---------------------------------------------------------------------

data class CohortsState(
    val loading: Boolean = true,
    val cohorts: List<EntitlementCohort> = emptyList(),
    /** Library names, so a cohort's libraries read as names and not numbers. */
    val libraries: List<OrganizationLibrary> = emptyList(),
    /** The web asks for archived cohorts too and files them under their own heading. */
    val includeArchived: Boolean = true,
    val error: ErrorText? = null,
)

class CohortsViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(CohortsState())
    val state: StateFlow<CohortsState> = _state

    suspend fun load(session: OrganizationSession, includeArchived: Boolean = _state.value.includeArchived) {
        val request = gate.begin()
        _state.update { it.copy(loading = true, includeArchived = includeArchived, error = null) }
        val outcome = session.attempt(R.string.organization_error_cohorts) { session.client.listOrganizationCohorts(includeArchived) }
        val libraries = if (_state.value.libraries.isEmpty()) {
            (session.attempt(R.string.organization_error_libraries) { session.client.listOrganizationLibraries() } as? Outcome.Ok)?.value.orEmpty()
        } else _state.value.libraries
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(loading = false, cohorts = outcome.value, libraries = libraries)
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message, libraries = libraries)
            }
        }
    }
}

// ---- Policy decisions (read-only) -------------------------------------------------------------------

data class DecisionsState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val decisions: List<PolicyDecision> = emptyList(),
    val nextCursor: String? = null,
    val error: ErrorText? = null,
    val libraries: List<OrganizationLibrary> = emptyList(),
    /** The check the administrator opened, once its detail has loaded. */
    val selectedId: Long? = null,
    val detail: PolicyDecisionDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: ErrorText? = null,
)

class DecisionsViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(DecisionsState())
    val state: StateFlow<DecisionsState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.decisions.isEmpty(), loadingMore = false, error = null) }
        val outcome = session.attempt(R.string.organization_error_decisions) { session.client.listOrganizationPolicyDecisions(null) }
        val libraries = if (_state.value.libraries.isEmpty()) {
            (session.attempt(R.string.organization_error_libraries) { session.client.listOrganizationLibraries() } as? Outcome.Ok)?.value.orEmpty()
        } else _state.value.libraries
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(
                    loading = false, decisions = outcome.value.decisions, nextCursor = outcome.value.next_cursor?.ifBlank { null },
                    libraries = libraries,
                )
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message, libraries = libraries)
            }
        }
    }

    /** Opens one check: the list row shows the gist, the route behind it the rules and the libraries it could reach. */
    suspend fun open(session: OrganizationSession, id: Long) {
        _state.update { it.copy(selectedId = id, detail = null, detailLoading = true, detailError = null) }
        val outcome = session.attempt(R.string.organization_error_decision_detail) { session.client.getOrganizationPolicyDecision(id) }
        _state.update {
            if (it.selectedId != id) it
            else when (outcome) {
                is Outcome.Ok -> it.copy(detail = outcome.value, detailLoading = false)
                is Outcome.Failed -> it.copy(detailLoading = false, detailError = outcome.message)
            }
        }
    }

    fun close() = _state.update { it.copy(selectedId = null, detail = null, detailLoading = false, detailError = null) }

    suspend fun loadMore(session: OrganizationSession) {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        val request = gate.current()
        _state.update { it.copy(loadingMore = true, error = null) }
        val outcome = session.attempt(R.string.organization_error_decisions) { session.client.listOrganizationPolicyDecisions(cursor) }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(
                    loadingMore = false,
                    decisions = it.decisions + outcome.value.decisions.filter { next -> it.decisions.none { seen -> seen.id == next.id } },
                    nextCursor = outcome.value.next_cursor?.ifBlank { null },
                )
                is Outcome.Failed -> it.copy(loadingMore = false, error = outcome.message)
            }
        }
    }
}
