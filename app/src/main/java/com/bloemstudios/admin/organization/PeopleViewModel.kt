package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createOrganizationSelection
import com.bloemstudios.admin.network.api.getOrganizationBulkJob
import com.bloemstudios.admin.network.api.listOrganizationGroups
import com.bloemstudios.admin.network.api.listOrganizationPeople
import com.bloemstudios.admin.network.api.setOrganizationMembershipStatus
import com.bloemstudios.admin.network.api.setOrganizationProfileGroup
import com.bloemstudios.admin.network.api.startOrganizationBulkJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The three changes the web's bulk bar offers. */
sealed interface BulkAction {
    val kind: String

    data object Suspend : BulkAction { override val kind = BulkKind.SUSPEND }
    data object Reactivate : BulkAction { override val kind = BulkKind.REACTIVATE }
    data class AssignGroup(val group: AccessGroup) : BulkAction { override val kind = BulkKind.ASSIGN_GROUP }
}

/**
 * What a bulk change has done so far. [job] is the server's own job for "everyone who matches", or one the app
 * keeps itself while it changes the people the administrator picked one by one; both read the same.
 */
data class BulkReport(
    val action: BulkAction,
    val job: BulkJob,
    /** Names for the accounts the app knows, so a result row can say "Sam" and not just "Account 5". */
    val names: Map<Long, String> = emptyMap(),
    /** Set when following the job failed; what the job did by then is shown as it stood. */
    val error: ErrorText? = null,
) {
    val running: Boolean get() = !job.isFinished && error == null
}

data class PeopleState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val people: List<PersonSummary> = emptyList(),
    val nextCursor: String? = null,
    val approximateTotal: Long = 0,
    val filter: PeopleFilter = PeopleFilter(),
    /** Access groups, for the filter and for moving a profile. */
    val groups: List<AccessGroup> = emptyList(),
    val pendingAccountIds: Set<Long> = emptySet(),
    val error: ErrorText? = null,
    /** A confirmation of the last change, or the reason the list was reloaded. */
    val notice: ErrorText? = null,
    /** True while the administrator is choosing people for a bulk change. */
    val selecting: Boolean = false,
    /** People ticked one by one. */
    val pickedIds: Set<Long> = emptySet(),
    /** Everyone the filter matched when "Select all" was pressed; changed by one server job. */
    val matching: PeopleSelection? = null,
    val makingSelection: Boolean = false,
    val report: BulkReport? = null,
) {
    /** How many people a bulk change would touch right now. */
    val selectedCount: Long get() = matching?.matched ?: pickedIds.size.toLong()
    val hasSelection: Boolean get() = matching != null || pickedIds.isNotEmpty()
    val bulkRunning: Boolean get() = report?.running == true
}

/**
 * People: search, filter by status, access group and recent activity, sort, suspend or reactivate a membership,
 * move a profile to an access group, and the web's bulk actions. Writes quote the person's `security_revision`,
 * one at a time per person, and never replay. There is no password reset: the server offers none to
 * organization administrators.
 *
 * Bulk changes come in the web's two shapes. "Select all N" asks the server to freeze everyone the filter
 * matches and runs one server job over that selection ([BulkJob], followed until it finishes). People ticked one
 * by one cannot be named to the server's job (the route takes a filter, not account ids), so those get the same
 * single-person writes the row buttons use, one after another, with the same result summary.
 */
class PeopleViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state

    /** The time "active in the last N days" counts back from. */
    internal var clock: () -> java.time.Instant = java.time.Instant::now

    /**
     * The filter as the server takes it: "last N days" becomes one instant, fixed when the filter is applied so
     * every page of the list (whose cursor remembers the filter) and any selection use the same one.
     */
    private fun effective(filter: PeopleFilter): PeopleFilter =
        filter.activeWithinDays?.let { days -> filter.copy(activeSince = clock().minus(java.time.Duration.ofDays(days.toLong())).toString()) } ?: filter

    /** How long to wait between looks at a running server job. */
    internal var pollDelayMillis: Long = 1_000

    /** Looks at one job at most this often before giving up; the job itself keeps running on the server. */
    internal var maxPolls: Int = 600

    /** Loads the groups once, then the first page with [filter]. A different filter drops the selection, as the web does. */
    suspend fun load(session: OrganizationSession, requested: PeopleFilter = _state.value.filter) {
        val request = gate.begin()
        val filter = effective(requested)
        val changedFilter = _state.value.filter.copy(activeSince = null) != filter.copy(activeSince = null)
        _state.update {
            it.copy(
                loading = it.people.isEmpty() || changedFilter, loadingMore = false, filter = filter, error = null,
                matching = if (changedFilter) null else it.matching,
                pickedIds = if (changedFilter) emptySet() else it.pickedIds,
            )
        }
        val groups = if (_state.value.groups.isEmpty()) {
            when (val outcome = session.attempt(R.string.organization_error_groups) { session.client.listOrganizationGroups() }) {
                is Outcome.Ok -> outcome.value
                // A failure to read groups only disables the group controls; people still load.
                is Outcome.Failed -> emptyList()
            }
        } else _state.value.groups
        val outcome = session.attempt(R.string.organization_error_people) { session.client.listOrganizationPeople(filter, null) }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(
                    loading = false, people = outcome.value.items, nextCursor = outcome.value.next_cursor?.ifBlank { null },
                    approximateTotal = outcome.value.approximate_total, groups = groups,
                    pickedIds = it.pickedIds.intersect(outcome.value.items.map { person -> person.account_id }.toSet()),
                )
                is Outcome.Failed -> it.copy(loading = false, groups = groups, error = outcome.message)
            }
        }
    }

    suspend fun loadMore(session: OrganizationSession) {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        val request = gate.current()
        val filter = _state.value.filter
        _state.update { it.copy(loadingMore = true, error = null) }
        val outcome = session.attempt(R.string.organization_error_people) { session.client.listOrganizationPeople(filter, cursor) }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(
                    loadingMore = false,
                    people = it.people + outcome.value.items.filter { next -> it.people.none { seen -> seen.account_id == next.account_id } },
                    nextCursor = outcome.value.next_cursor?.ifBlank { null },
                )
                is Outcome.Failed -> it.copy(loadingMore = false, error = outcome.message)
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }
    fun dismissReport() = _state.update { if (it.bulkRunning) it else it.copy(report = null) }

    // ---- Choosing people ------------------------------------------------------------------------------------

    fun startSelecting() = _state.update { it.copy(selecting = true, report = if (it.bulkRunning) it.report else null) }

    fun stopSelecting() = _state.update { if (it.bulkRunning) it else it.copy(selecting = false, pickedIds = emptySet(), matching = null) }

    /** Ticks or unticks one person. Choosing by hand replaces a "select all" selection. */
    fun toggle(accountId: Long) = _state.update {
        if (it.bulkRunning) it
        else it.copy(
            selecting = true, matching = null,
            pickedIds = if (accountId in it.pickedIds) it.pickedIds - accountId else it.pickedIds + accountId,
        )
    }

    /** "Select all N": the server freezes everyone the current filter matches. Nothing changes yet. */
    suspend fun selectAllMatching(session: OrganizationSession) {
        if (_state.value.makingSelection || _state.value.bulkRunning) return
        val filter = _state.value.filter
        _state.update { it.copy(makingSelection = true, error = null, notice = null, selecting = true) }
        val outcome = session.attempt(R.string.organization_error_selection) { session.client.createOrganizationSelection(filter) }
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(makingSelection = false, matching = outcome.value, pickedIds = emptySet())
                is Outcome.Failed -> it.copy(makingSelection = false, error = outcome.message)
            }
        }
    }

    // ---- Changing the chosen people -------------------------------------------------------------------------

    /** Applies [action] to the chosen people. Needs a selection; one change at a time. */
    suspend fun apply(session: OrganizationSession, action: BulkAction) {
        val snapshot = _state.value
        if (snapshot.bulkRunning || !snapshot.hasSelection) return
        val matching = snapshot.matching
        if (matching != null) runServerJob(session, action, matching, snapshot.people) else {
            runPicked(session, action, snapshot.people.filter { it.account_id in snapshot.pickedIds })
        }
    }

    private suspend fun runServerJob(session: OrganizationSession, action: BulkAction, selection: PeopleSelection, known: List<PersonSummary>) {
        val names = known.associate { it.account_id to it.label }
        val groupId = (action as? BulkAction.AssignGroup)?.group?.id?.toLongOrNull()
        val started = try {
            session.write { client, _ -> client.startOrganizationBulkJob(selection.token, action.kind, groupId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val expired = (error is ApiError && error.statusCode == 409) || error is OrganizationStateChanged
            val message = if (expired) ErrorText.Res(R.string.organization_selection_expired)
            else session.failureText(error, R.string.organization_error_bulk_start)
            // An expired selection can't be used again; the rest of the failures leave it for another try.
            _state.update { it.copy(error = message, matching = if (expired) null else it.matching) }
            return
        }
        _state.update { it.copy(report = BulkReport(action, started, names), notice = null, error = null) }
        var job = started
        var polls = 0
        var failedLooks = 0
        while (!job.isFinished && polls < maxPolls) {
            delay(pollDelayMillis)
            polls += 1
            when (val look = session.attempt(R.string.organization_error_bulk_progress) { session.client.getOrganizationBulkJob(job.job_id) }) {
                is Outcome.Ok -> {
                    failedLooks = 0
                    job = look.value
                    _state.update { it.copy(report = BulkReport(action, job, names)) }
                }
                is Outcome.Failed -> {
                    failedLooks += 1
                    if (failedLooks >= MAX_FAILED_LOOKS) {
                        _state.update { it.copy(report = BulkReport(action, job, names, error = look.message)) }
                        return
                    }
                }
            }
        }
        if (!job.isFinished) {
            _state.update { it.copy(report = BulkReport(action, job, names, error = ErrorText.Res(R.string.organization_bulk_still_running))) }
        }
        _state.update { it.copy(selecting = false, matching = null, pickedIds = emptySet()) }
        load(session)
    }

    private suspend fun runPicked(session: OrganizationSession, action: BulkAction, targets: List<PersonSummary>) {
        val names = targets.associate { it.account_id to it.label }
        var job = BulkJob(job_id = LOCAL_JOB, status = "running", progress_total = targets.size.toLong())
        _state.update { it.copy(report = BulkReport(action, job, names), notice = null, error = null) }
        val skipped = mutableListOf<BulkRecordResult>()
        val failed = mutableListOf<BulkRecordResult>()
        var succeeded = 0L
        var aborted: ErrorText? = null
        for ((index, person) in targets.withIndex()) {
            when (val result = changeOne(session, action, person)) {
                is OneResult.Changed -> {
                    succeeded += 1
                    _state.update { state -> state.copy(people = state.people.map { if (it.account_id == person.account_id) result.person else it }) }
                }
                is OneResult.Skipped -> skipped += BulkRecordResult(person.account_id, result.reason)
                is OneResult.Failed -> failed += BulkRecordResult(person.account_id, result.reason)
                is OneResult.Abort -> { aborted = result.message }
            }
            if (aborted != null) break
            job = job.copy(progress_current = index + 1L, succeeded = succeeded, skipped = skipped.toList(), failed = failed.toList())
            _state.update { it.copy(report = BulkReport(action, job, names)) }
        }
        val status = if (aborted != null) "failed" else "completed"
        job = job.copy(status = status, succeeded = succeeded, skipped = skipped.toList(), failed = failed.toList())
        _state.update { it.copy(report = BulkReport(action, job, names, error = aborted), selecting = false, pickedIds = emptySet(), matching = null) }
        load(session)
    }

    private sealed interface OneResult {
        data class Changed(val person: PersonSummary) : OneResult
        data class Skipped(val reason: String) : OneResult
        data class Failed(val reason: String) : OneResult
        data class Abort(val message: ErrorText) : OneResult
    }

    private suspend fun changeOne(session: OrganizationSession, action: BulkAction, person: PersonSummary): OneResult = when (action) {
        BulkAction.Suspend -> when {
            // Suspending oneself would end this session; the server's own job skips the actor the same way.
            person.isSelf(session.context.value) -> OneResult.Skipped("actor_authority_target")
            person.isSuspended -> OneResult.Skipped("already_applied")
            else -> write(session, person, protectedReason = "protected_owner") { client, revision ->
                client.setOrganizationMembershipStatus(person.account_id, PersonSummary.MEMBERSHIP_SUSPENDED, revision)
            }
        }
        BulkAction.Reactivate -> when {
            person.isActive -> OneResult.Skipped("already_applied")
            !person.isSuspended -> OneResult.Skipped("not_suspended")
            else -> write(session, person, protectedReason = "mutation_failed") { client, revision ->
                client.setOrganizationMembershipStatus(person.account_id, PersonSummary.MEMBERSHIP_ACTIVE, revision)
            }
        }
        is BulkAction.AssignGroup -> assignAll(session, person, action.group)
    }

    /** Moves every profile that is not already in [group], one write each, quoting the revision the last write returned. */
    private suspend fun assignAll(session: OrganizationSession, person: PersonSummary, group: AccessGroup): OneResult {
        val groupId = group.id.toLongOrNull() ?: return OneResult.Failed("mutation_failed")
        if (person.profiles.isEmpty()) return OneResult.Skipped("no_profiles")
        val pending = person.profiles.filter { it.group_id != groupId }
        if (pending.isEmpty()) return OneResult.Skipped("already_applied")
        var current = person
        for (profile in pending) {
            when (val result = write(session, current, protectedReason = "mutation_failed") { client, revision ->
                client.setOrganizationProfileGroup(current.account_id, profile.id, groupId, revision)
            }) {
                is OneResult.Changed -> current = result.person
                else -> return result
            }
        }
        return OneResult.Changed(current)
    }

    private suspend fun write(
        session: OrganizationSession,
        person: PersonSummary,
        protectedReason: String,
        block: suspend (client: ApiClient, revision: Long) -> PersonSummary,
    ): OneResult = try {
        OneResult.Changed(session.write { client, _ -> block(client, person.security_revision) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: OrganizationStateChanged) {
        OneResult.Failed("authorization_state_changed")
    } catch (error: OrganizationAccessDenied) {
        OneResult.Abort(session.failureText(error, R.string.organization_error_person_change))
    } catch (error: ApiError) {
        when (error.statusCode) {
            403 -> OneResult.Abort(session.failureText(error, R.string.organization_error_person_change))
            404 -> OneResult.Failed("not_found")
            422 -> OneResult.Failed(protectedReason)
            else -> OneResult.Failed("mutation_failed")
        }
    } catch (error: Exception) {
        OneResult.Failed("mutation_failed")
    }

    // ---- One person ---------------------------------------------------------------------------------------------

    /** Suspends ([suspend] true) or reactivates a membership. */
    suspend fun setSuspended(session: OrganizationSession, person: PersonSummary, suspend: Boolean) {
        // An administrator never suspends their own membership from here: it would end this session.
        if (suspend && person.isSelf(session.context.value)) return
        val status = if (suspend) PersonSummary.MEMBERSHIP_SUSPENDED else PersonSummary.MEMBERSHIP_ACTIVE
        change(session, person, R.string.organization_error_person_change, R.string.organization_error_cannot_suspend,
            if (suspend) R.string.organization_notice_suspended else R.string.organization_notice_reactivated) { client, _ ->
            client.setOrganizationMembershipStatus(person.account_id, status, person.security_revision)
        }
    }

    /** Moves one profile of [person] to [group]. */
    suspend fun assignGroup(session: OrganizationSession, person: PersonSummary, profile: ProfileSummary, group: AccessGroup) {
        val groupId = group.id.toLongOrNull() ?: return
        change(session, person, R.string.organization_error_person_change, null, R.string.organization_notice_group_changed) { client, _ ->
            client.setOrganizationProfileGroup(person.account_id, profile.id, groupId, person.security_revision)
        }
    }

    private suspend fun change(
        session: OrganizationSession,
        person: PersonSummary,
        fallback: Int,
        refused: Int?,
        success: Int,
        write: suspend (client: ApiClient, revision: Long) -> PersonSummary,
    ) {
        if (person.account_id in _state.value.pendingAccountIds || _state.value.bulkRunning) return
        _state.update { it.copy(pendingAccountIds = it.pendingAccountIds + person.account_id, error = null, notice = null) }
        val outcome = session.attempt(fallback, refused) { session.write(write) }
        _state.update { it.copy(pendingAccountIds = it.pendingAccountIds - person.account_id) }
        when (outcome) {
            is Outcome.Ok -> _state.update { state ->
                state.copy(
                    people = state.people.map { if (it.account_id == person.account_id) outcome.value else it },
                    notice = ErrorText.Res(success),
                )
            }
            is Outcome.Failed -> {
                // The person may have changed under us: show what the server now holds, then say why.
                load(session)
                _state.update { it.copy(error = outcome.message) }
            }
        }
    }

    private companion object {
        const val LOCAL_JOB = "local"
        const val MAX_FAILED_LOOKS = 3
    }
}
