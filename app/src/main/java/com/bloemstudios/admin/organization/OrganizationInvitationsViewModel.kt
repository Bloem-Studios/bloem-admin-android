package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createOrganizationInvitation
import com.bloemstudios.admin.network.api.listOrganizationGroups
import com.bloemstudios.admin.network.api.listOrganizationInvitations
import com.bloemstudios.admin.network.api.resendOrganizationInvitation
import com.bloemstudios.admin.network.api.revokeOrganizationInvitation
import io.ktor.http.encodeURLPathPart
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The one-time claim link of a sent or renewed invitation. The server shows the token once and never
 * again, so this is held in memory only, until the administrator dismisses it or leaves.
 */
data class ClaimLink(val invitation: OrganizationInvitation, val url: String) {
    override fun toString(): String = "ClaimLink(invitation=${invitation.id}, url=<redacted>)"
}

/** `https://<server>/invite/<token>`: the server hands back the token, the web builds this same link. */
fun claimUrlFor(origin: String, token: String): String = "$origin/invite/${token.encodeURLPathPart()}"

data class InvitationsState(
    val loading: Boolean = true,
    val invitations: List<OrganizationInvitation> = emptyList(),
    val groups: List<AccessGroup> = emptyList(),
    val busy: Boolean = false,
    val error: ErrorText? = null,
    val claimLink: ClaimLink? = null,
)

sealed interface InvitationResult {
    data class Sent(val link: ClaimLink) : InvitationResult
    data class Failed(val message: ErrorText) : InvitationResult
}

/**
 * Send, renew and revoke organization invitations. Organization invitations always create a member
 * (`role=user`) and nothing is emailed: the administrator gets the link and hands it over. Create and
 * renew are submitted once and never retried, since the claim token is shown only once.
 */
class OrganizationInvitationsViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val surfaceEpoch = AtomicLong(0)
    private val _state = MutableStateFlow(InvitationsState())
    val state: StateFlow<InvitationsState> = _state

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        _state.update { it.copy(loading = it.invitations.isEmpty(), error = null) }
        val outcome = session.attempt(R.string.organization_error_invitations) { session.client.listOrganizationInvitations() }
        val groups = if (_state.value.groups.isEmpty()) {
            (session.attempt(R.string.organization_error_groups) { session.client.listOrganizationGroups() } as? Outcome.Ok)?.value.orEmpty()
        } else _state.value.groups
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(loading = false, invitations = outcome.value, groups = groups)
                is Outcome.Failed -> it.copy(loading = false, groups = groups, error = outcome.message)
            }
        }
    }

    suspend fun send(session: OrganizationSession, email: String, accessGroupId: Long?, note: String?): InvitationResult {
        val address = email.trim()
        if (address.isEmpty()) return InvitationResult.Failed(ErrorText.Res(R.string.organization_error_email_required))
        return handoff(session, R.string.organization_error_invite_unconfirmed) { client, revision ->
            client.createOrganizationInvitation(CreateOrganizationInvitation(address, revision, accessGroupId, note?.trim()?.ifEmpty { null }))
        }
    }

    suspend fun resend(session: OrganizationSession, invitation: OrganizationInvitation): InvitationResult =
        handoff(session, R.string.organization_error_invite_unconfirmed) { client, revision ->
            client.resendOrganizationInvitation(invitation.id, revision)
        }

    suspend fun revoke(session: OrganizationSession, invitation: OrganizationInvitation): Boolean {
        _state.update { it.copy(busy = true, error = null) }
        val outcome = session.attempt(R.string.organization_error_revoke) {
            session.write { client, revision -> client.revokeOrganizationInvitation(invitation.id, revision) }
        }
        _state.update { it.copy(busy = false) }
        load(session)
        _state.update { it.copy(error = (outcome as? Outcome.Failed)?.message) }
        return outcome is Outcome.Ok
    }

    private suspend fun handoff(
        session: OrganizationSession,
        fallback: Int,
        write: suspend (client: com.bloemstudios.admin.network.ApiClient, revision: Long) -> InvitationHandoff,
    ): InvitationResult {
        val epoch = surfaceEpoch.get()
        _state.update { it.copy(busy = true, error = null) }
        val outcome = session.attempt(fallback, R.string.organization_error_invite_refused) { session.write(write) }
        return when (outcome) {
            is Outcome.Ok -> {
                // Keep the link before anything that can be cancelled: it is never shown again.
                val link = ClaimLink(outcome.value.invitation, claimUrlFor(session.origin.value, outcome.value.claim_token))
                _state.update { it.copy(busy = false, claimLink = if (surfaceEpoch.get() == epoch) link else null) }
                load(session)
                InvitationResult.Sent(link)
            }
            is Outcome.Failed -> {
                _state.update { it.copy(busy = false) }
                // A state change means the list is stale; an uncertain failure needs the real list.
                load(session)
                _state.update { it.copy(error = outcome.message) }
                InvitationResult.Failed(outcome.message)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissClaimLink() = _state.update { it.copy(claimLink = null) }

    /** Releases the claim link and invalidates anything still in flight. */
    fun closeSurface() {
        surfaceEpoch.incrementAndGet()
        dismissClaimLink()
    }

    /** Keep the link only while Android recreates the same screen (rotation). */
    fun onSurfaceHidden(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) closeSurface()
    }

    override fun onCleared() {
        closeSurface()
        super.onCleared()
    }
}
