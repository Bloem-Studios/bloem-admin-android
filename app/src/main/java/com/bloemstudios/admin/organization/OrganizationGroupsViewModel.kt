package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createOrganizationGroup
import com.bloemstudios.admin.network.api.deleteOrganizationGroup
import com.bloemstudios.admin.network.api.listOrganizationGroups
import com.bloemstudios.admin.network.api.listOrganizationLibraries
import com.bloemstudios.admin.network.api.updateOrganizationGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AccessGroupsState(
    val loading: Boolean = true,
    val groups: List<AccessGroup> = emptyList(),
    /** The libraries the organization has, for choosing which ones a group reaches. */
    val libraries: List<OrganizationLibrary> = emptyList(),
    val saving: Boolean = false,
    val error: ErrorText? = null,
    val notice: ErrorText? = null,
    /** The group a "New group" just made, so the screen can open it. Consumed with [AccessGroupsViewModel.consumeCreated]. */
    val createdId: String? = null,
    /** Set when a delete moved profiles to the default group, so the notice can say how many. */
    val lastDeletion: GroupDeletionImpact? = null,
)

/**
 * Access groups as the organization routes allow: list, create, edit and delete, as the web's organization page does.
 * Writes quote the organization policy revision, are sent once, and after each one the pass is renewed so the
 * next write quotes what the server holds now. The default group cannot be deleted and a group the server manages
 * through an entitlement plan cannot be changed; the server says so and its words are shown.
 */
class AccessGroupsViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(AccessGroupsState())
    val state: StateFlow<AccessGroupsState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.groups.isEmpty(), error = null) }
        val outcome = session.attempt(R.string.organization_error_groups) { session.client.listOrganizationGroups() }
        // Libraries only feed the editor's choice list; the page works without them.
        val libraries = (session.attempt(R.string.organization_error_libraries) { session.client.listOrganizationLibraries() } as? Outcome.Ok)?.value
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(loading = false, groups = outcome.value, libraries = libraries ?: it.libraries)
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message, libraries = libraries ?: it.libraries)
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null, lastDeletion = null) }
    fun consumeCreated() = _state.update { it.copy(createdId = null) }

    suspend fun create(session: OrganizationSession, name: String) {
        if (_state.value.saving) return
        if (name.isBlank()) {
            _state.update { it.copy(error = ErrorText.Res(R.string.organization_error_group_name)) }
            return
        }
        _state.update { it.copy(saving = true, error = null, notice = null, lastDeletion = null) }
        val outcome = session.attempt(R.string.organization_error_group_save, R.string.organization_error_group_refused) {
            session.write { client, revision -> client.createOrganizationGroup(name, revision) }
        }
        finish(session, outcome, R.string.organization_notice_group_created) { group -> _state.update { it.copy(createdId = group.id) } }
    }

    suspend fun save(session: OrganizationSession, group: AccessGroup, draft: AccessGroupDraft) {
        if (_state.value.saving) return
        val id = group.id.toLongOrNull() ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(error = ErrorText.Res(R.string.organization_error_group_name)) }
            return
        }
        _state.update { it.copy(saving = true, error = null, notice = null, lastDeletion = null) }
        val outcome = session.attempt(R.string.organization_error_group_save, R.string.organization_error_group_refused) {
            session.write { client, revision -> client.updateOrganizationGroup(id, draft.toBody(revision)) }
        }
        finish(session, outcome, R.string.organization_notice_group_saved) {}
    }

    suspend fun delete(session: OrganizationSession, group: AccessGroup): Boolean {
        if (_state.value.saving) return false
        val id = group.id.toLongOrNull() ?: return false
        _state.update { it.copy(saving = true, error = null, notice = null, lastDeletion = null) }
        val outcome = session.attempt(R.string.organization_error_group_delete, R.string.organization_error_group_refused) {
            session.write { client, revision -> client.deleteOrganizationGroup(id, revision) }
        }
        finish(session, outcome, R.string.organization_notice_group_deleted) { impact -> _state.update { it.copy(lastDeletion = impact) } }
        return outcome is Outcome.Ok
    }

    private suspend fun <T> finish(session: OrganizationSession, outcome: Outcome<T>, success: Int, onOk: (T) -> Unit) {
        when (outcome) {
            is Outcome.Ok -> {
                onOk(outcome.value)
                _state.update { it.copy(saving = false, notice = ErrorText.Res(success)) }
                renewPass(session)
            }
            is Outcome.Failed -> _state.update { it.copy(saving = false) }
        }
        // Show what the server holds, whether or not the write went through; a load clears the error, so say it after.
        load(session)
        if (outcome is Outcome.Failed) _state.update { it.copy(error = outcome.message) }
    }

    /** A group change can move the organization's policy revision; the next write must quote the new one. */
    private suspend fun renewPass(session: OrganizationSession) {
        try {
            session.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The next call renews it again if this one failed.
        }
    }
}
