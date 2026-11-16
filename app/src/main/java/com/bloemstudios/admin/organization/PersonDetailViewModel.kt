package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import androidx.annotation.StringRes
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.addSupportProfile
import com.bloemstudios.admin.network.api.clearSupportProfilePin
import com.bloemstudios.admin.network.api.createSupportPasswordReset
import com.bloemstudios.admin.network.api.getOrganizationPerson
import com.bloemstudios.admin.network.api.listSupportDevices
import com.bloemstudios.admin.network.api.listSupportSessions
import com.bloemstudios.admin.network.api.removeSupportProfile
import com.bloemstudios.admin.network.api.renameSupportProfile
import com.bloemstudios.admin.network.api.signOutSupportDevice
import com.bloemstudios.admin.network.api.signOutSupportEverywhere
import com.bloemstudios.admin.network.api.signOutSupportSession
import com.bloemstudios.admin.network.api.listOrganizationActivity
import com.bloemstudios.admin.network.api.listOrganizationGroups
import com.bloemstudios.admin.network.api.setOrganizationMembershipStatus
import com.bloemstudios.admin.network.api.setOrganizationProfileGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A list the support routes fill: not asked for yet, shown, or not available (and why the page says nothing about it). */
data class SupportList<out T>(val loaded: Boolean = false, val items: List<T> = emptyList(), val unavailable: Boolean = false)

/**
 * The one-time link the server returned for a reset. It is a secret: held in memory until the administrator closes
 * the dialog (or leaves), never saved, and hidden from [toString].
 */
data class ResetLink(val personLabel: String, val url: String, val expiresAt: String) {
    override fun toString(): String = "ResetLink(person=$personLabel, expiresAt=$expiresAt, url=<redacted>)"
}

data class PersonDetailState(
    val loading: Boolean = true,
    val person: PersonSummary? = null,
    /** The person is not (or no longer) in this organization: the server answered 404. */
    val gone: Boolean = false,
    val groups: List<AccessGroup> = emptyList(),
    /** Changes to this person's membership found in the newest organization activity. */
    val changes: List<OrganizationAuditEvent> = emptyList(),
    /** How many of the newest activity entries were looked through, so the list can say what it covers. */
    val changesLooked: Int = 0,
    val changesUnavailable: Boolean = false,
    val pending: Boolean = false,
    val error: ErrorText? = null,
    val notice: ErrorText? = null,
    /** What this server can do for support; nothing until it has said so. */
    val capabilities: SupportCapabilities = SupportCapabilities.NONE,
    val sessions: SupportList<SupportSession> = SupportList(),
    val devices: SupportList<SupportDevice> = SupportList(),
    /** Why the server will not let this administrator help this person (an administrator, the owner, themselves). */
    val protectedMessage: ErrorText? = null,
    val resetLink: ResetLink? = null,
)

/**
 * One person, from the routes an organization administrator has: the person (membership, profiles with their
 * access groups, last activity) and the organization's newest activity, narrowed to changes made to this person's
 * membership. The server offers no per-person activity route, so that part is "among the latest changes".
 *
 * Where the server offers the support routes (see [SupportCapabilities]) the page also helps the person get back in
 * and fixes their profiles: reset password (email, or a one-time link shown once), sign-ins and devices with sign out,
 * and profile add, rename, clear PIN and remove. Every write goes through [OrganizationSession.write], so it is sent
 * once with the revision the page loaded and never replayed; a conflict reloads the person and says so.
 */
class PersonDetailViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(PersonDetailState())
    val state: StateFlow<PersonDetailState> = _state

    suspend fun load(session: OrganizationSession, accountId: Long) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.person == null, error = null) }
        val person = try {
            Outcome.Ok(session.client.getOrganizationPerson(accountId))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ApiError) {
            if (error.statusCode == 404) {
                if (!gate.isCurrent(request)) return
                _state.update { it.copy(loading = false, person = null, gone = true) }
                return
            }
            Outcome.Failed(session.failureText(error, R.string.organization_error_person))
        } catch (error: Exception) {
            Outcome.Failed(session.failureText(error, R.string.organization_error_person))
        }
        if (person is Outcome.Failed) {
            if (!gate.isCurrent(request)) return
            _state.update { it.copy(loading = false, error = person.message) }
            return
        }
        val loaded = (person as Outcome.Ok).value
        session.loadSupportCapabilities()
        val groups = if (_state.value.groups.isEmpty()) {
            (session.attempt(R.string.organization_error_groups) { session.client.listOrganizationGroups() } as? Outcome.Ok)?.value.orEmpty()
        } else _state.value.groups
        val activity = session.attempt(R.string.organization_error_activity) { session.client.listOrganizationActivity(null) }
        if (!gate.isCurrent(request)) return
        _state.update {
            it.copy(
                loading = false, person = loaded, gone = false, groups = groups,
                changes = (activity as? Outcome.Ok)?.value?.events.orEmpty().filter { event -> event.target_id == loaded.membership_id },
                changesLooked = (activity as? Outcome.Ok)?.value?.events?.size ?: 0,
                changesUnavailable = activity is Outcome.Failed,
                capabilities = session.supportCapabilities.value,
            )
        }
        loadSupportLists(session, loaded)
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    suspend fun setSuspended(session: OrganizationSession, suspend: Boolean) {
        val person = _state.value.person ?: return
        if (suspend && person.isSelf(session.context.value)) return
        val status = if (suspend) PersonSummary.MEMBERSHIP_SUSPENDED else PersonSummary.MEMBERSHIP_ACTIVE
        change(session, R.string.organization_error_cannot_suspend, if (suspend) R.string.organization_notice_suspended else R.string.organization_notice_reactivated) { client, _ ->
            client.setOrganizationMembershipStatus(person.account_id, status, person.security_revision)
        }
    }

    suspend fun assignGroup(session: OrganizationSession, profile: ProfileSummary, group: AccessGroup) {
        val person = _state.value.person ?: return
        val groupId = group.id.toLongOrNull() ?: return
        change(session, null, R.string.organization_notice_group_changed) { client, _ ->
            client.setOrganizationProfileGroup(person.account_id, profile.id, groupId, person.security_revision)
        }
    }

    private suspend fun change(
        session: OrganizationSession,
        refused: Int?,
        success: Int,
        write: suspend (client: com.bloemstudios.admin.network.ApiClient, revision: Long) -> PersonSummary,
    ) {
        val person = _state.value.person ?: return
        if (_state.value.pending) return
        _state.update { it.copy(pending = true, error = null, notice = null) }
        val outcome = session.attempt(R.string.organization_error_person_change, refused) { session.write(write) }
        when (outcome) {
            is Outcome.Ok -> {
                _state.update { it.copy(pending = false, person = outcome.value, notice = ErrorText.Res(success)) }
                // The change is on the record now; read the newest activity again.
                refreshChanges(session, outcome.value)
            }
            is Outcome.Failed -> {
                _state.update { it.copy(pending = false) }
                // The person may have changed under us: show what the server now holds, then say why.
                load(session, person.account_id)
                _state.update { it.copy(error = outcome.message) }
            }
        }
    }

    private suspend fun refreshChanges(session: OrganizationSession, person: PersonSummary) {
        val activity = session.attempt(R.string.organization_error_activity) { session.client.listOrganizationActivity(null) }
        if (activity is Outcome.Ok) {
            _state.update {
                it.copy(
                    changes = activity.value.events.filter { event -> event.target_id == person.membership_id },
                    changesLooked = activity.value.events.size, changesUnavailable = false,
                )
            }
        }
    }

    // ---- Support: sign-ins, devices, reset, profiles -----------------------------------------------------------

    /** Whether the page offers support actions on [person]: the server has them, the person is active and not the viewer. */
    private fun offersSupport(session: OrganizationSession, person: PersonSummary): Boolean =
        session.supportCapabilities.value.hasPersonSupport && person.isActive && !person.isSelf(session.context.value)

    private suspend fun loadSupportLists(session: OrganizationSession, person: PersonSummary) {
        val caps = session.supportCapabilities.value
        if (!offersSupport(session, person)) {
            _state.update { it.copy(sessions = SupportList(), devices = SupportList(), protectedMessage = null) }
            return
        }
        var protectedBy: ErrorText? = null
        suspend fun <T> read(wanted: Boolean, fetch: suspend (ApiClient) -> List<T>): SupportList<T>? {
            if (!wanted) return null
            return try {
                SupportList(loaded = true, items = fetch(session.client))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: ApiError) {
                if (error.statusCode == 409 && error.code == "target_protected") protectedBy = session.failureText(error, R.string.organization_support_error_protected)
                SupportList(loaded = true, unavailable = true)
            } catch (_: Exception) {
                SupportList(loaded = true, unavailable = true)
            }
        }
        val sessions = read(caps.sessions) { it.listSupportSessions(person.account_id) }
        val devices = read(caps.devices) { it.listSupportDevices(person.account_id) }
        _state.update {
            it.copy(
                sessions = sessions ?: SupportList(), devices = devices ?: SupportList(),
                protectedMessage = protectedBy,
            )
        }
    }

    private suspend fun refreshSupportLists(session: OrganizationSession) {
        val person = _state.value.person ?: return
        loadSupportLists(session, person)
    }

    /**
     * Runs one support write. On success [onOk] shows its notice and takes in what the answer holds; the person and the lists
     * are then read again. On failure the person is read again first (it may have changed), then the reason is shown.
     */
    private suspend fun <T> support(
        session: OrganizationSession,
        @StringRes fallback: Int,
        @StringRes refused: Int? = null,
        write: suspend (client: ApiClient, person: PersonSummary) -> T,
        onOk: (T) -> Unit,
    ): Boolean {
        val person = _state.value.person ?: return false
        if (_state.value.pending) return false
        _state.update { it.copy(pending = true, error = null, notice = null) }
        val outcome = session.attempt(fallback, refused) { session.write { client, _ -> write(client, person) } }
        _state.update { it.copy(pending = false) }
        return when (outcome) {
            is Outcome.Ok -> {
                onOk(outcome.value)
                reloadAfterSupport(session, person.account_id)
                true
            }
            is Outcome.Failed -> {
                reloadAfterSupport(session, person.account_id)
                _state.update { it.copy(error = outcome.message) }
                false
            }
        }
    }

    /** Reads the person and the lists again without clearing a notice or a link already shown. */
    private suspend fun reloadAfterSupport(session: OrganizationSession, accountId: Long) {
        try {
            val fresh = session.client.getOrganizationPerson(accountId)
            _state.update { it.copy(person = fresh, gone = false) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ApiError) {
            // Gone from the organization, or the organization itself refused: the page says which (a refusal marks the session).
            if (error.statusCode == 404) _state.update { it.copy(gone = true, person = null) } else session.failureText(error, R.string.organization_error_person)
        } catch (_: Exception) {
            // Keep what is on screen; the next load reads again.
        }
        refreshSupportLists(session)
    }

    /** `delivery` is [ResetDelivery.EMAIL] or [ResetDelivery.LINK]; the person's revision is quoted, not raised. */
    suspend fun resetPassword(session: OrganizationSession, delivery: String): Boolean {
        val caps = session.supportCapabilities.value.password_reset
        if (delivery == ResetDelivery.EMAIL && !caps.email) return false
        if (delivery == ResetDelivery.LINK && !caps.link) return false
        return support(
            session, R.string.organization_support_error_reset,
            write = { client, person -> client.createSupportPasswordReset(person.account_id, delivery, person.security_revision) },
            onOk = { answer ->
                val label = answer.person.label
                val link = answer.reset.reset_url
                _state.update {
                    when {
                        delivery == ResetDelivery.LINK && !link.isNullOrBlank() ->
                            it.copy(person = answer.person, resetLink = ResetLink(label, link, answer.reset.expires_at))
                        delivery == ResetDelivery.EMAIL && answer.reset.delivery_status == "sent" ->
                            it.copy(person = answer.person, notice = ErrorText.Res(R.string.organization_support_notice_reset_sent))
                        delivery == ResetDelivery.EMAIL ->
                            it.copy(person = answer.person, notice = ErrorText.Res(R.string.organization_support_notice_reset_unconfirmed))
                        // A link the server issued but did not return cannot be shown again: say so rather than pretend.
                        else -> it.copy(person = answer.person, error = ErrorText.Res(R.string.organization_support_error_link_missing))
                    }
                }
            },
        )
    }

    fun dismissResetLink() = _state.update { it.copy(resetLink = null) }

    /** Signs the person out of every app; quotes their revision, which this raises. */
    suspend fun signOutEverywhere(session: OrganizationSession): Boolean {
        if (!session.supportCapabilities.value.sessions) return false
        return support(
            session, R.string.organization_support_error_signout,
            write = { client, person -> client.signOutSupportEverywhere(person.account_id, person.security_revision) },
            onOk = { answer ->
                _state.update { it.copy(person = answer.person, notice = ErrorText.Res(R.string.organization_support_notice_signed_out_all)) }
            },
        )
    }

    suspend fun signOutSession(session: OrganizationSession, target: SupportSession): Boolean {
        if (!session.supportCapabilities.value.sessions) return false
        return support(
            session, R.string.organization_support_error_signout,
            write = { client, person -> client.signOutSupportSession(person.account_id, target.id) },
            onOk = { _state.update { it.copy(notice = ErrorText.Res(R.string.organization_support_notice_signed_out)) } },
        )
    }

    suspend fun signOutDevice(session: OrganizationSession, target: SupportDevice): Boolean {
        if (!session.supportCapabilities.value.devices) return false
        return support(
            session, R.string.organization_support_error_signout,
            write = { client, person -> client.signOutSupportDevice(person.account_id, target.device_id) },
            onOk = { _state.update { it.copy(notice = ErrorText.Res(R.string.organization_support_notice_device_signed_out)) } },
        )
    }

    suspend fun addProfile(session: OrganizationSession, name: String, group: AccessGroup?): Boolean {
        if (!session.supportCapabilities.value.profiles.add) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = ErrorText.Res(R.string.organization_support_error_name), notice = null) }
            return false
        }
        return support(
            session, R.string.organization_support_error_profile, refused = R.string.organization_support_error_profile_refused,
            write = { client, person -> client.addSupportProfile(person.account_id, trimmed, group?.id?.toLongOrNull()) },
            onOk = { answer -> _state.update { it.copy(person = answer.person, notice = ErrorText.Res(R.string.organization_support_notice_profile_added)) } },
        )
    }

    suspend fun renameProfile(session: OrganizationSession, profile: ProfileSummary, name: String): Boolean {
        if (!session.supportCapabilities.value.profiles.rename) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = ErrorText.Res(R.string.organization_support_error_name), notice = null) }
            return false
        }
        return support(
            session, R.string.organization_support_error_profile, refused = R.string.organization_support_error_profile_refused,
            write = { client, person -> client.renameSupportProfile(person.account_id, profile.id, trimmed, profile.revision) },
            onOk = { answer -> _state.update { it.copy(person = answer.person, notice = ErrorText.Res(R.string.organization_support_notice_profile_renamed)) } },
        )
    }

    suspend fun clearPin(session: OrganizationSession, profile: ProfileSummary): Boolean {
        if (!session.supportCapabilities.value.profiles.clear_pin) return false
        return support(
            session, R.string.organization_support_error_profile,
            write = { client, person -> client.clearSupportProfilePin(person.account_id, profile.id, profile.revision) },
            onOk = { _state.update { it.copy(notice = ErrorText.Res(R.string.organization_support_notice_pin_cleared)) } },
        )
    }

    suspend fun removeProfile(session: OrganizationSession, profile: ProfileSummary): Boolean {
        if (!session.supportCapabilities.value.profiles.remove) return false
        if (profile.is_primary) return false
        return support(
            session, R.string.organization_support_error_profile,
            write = { client, person -> client.removeSupportProfile(person.account_id, profile.id, profile.revision) },
            onOk = { _state.update { it.copy(notice = ErrorText.Res(R.string.organization_support_notice_profile_removed)) } },
        )
    }

    /** Releases the one-time link when the page is left for good. */
    override fun onCleared() {
        _state.update { it.copy(resetLink = null) }
        super.onCleared()
    }
}
