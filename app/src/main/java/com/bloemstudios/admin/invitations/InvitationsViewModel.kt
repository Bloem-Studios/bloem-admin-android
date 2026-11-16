package com.bloemstudios.admin.invitations

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createInvitation
import com.bloemstudios.admin.network.api.listInvitations
import com.bloemstudios.admin.network.api.resendInvitation
import com.bloemstudios.admin.network.api.revokeInvitation
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

// send()/resend() share this result shape since both return an InvitationDelivery -- Success carries
// the claim_url and delivery_status through explicitly so the screen can show the one-time invite
// link, rather than relying on the reloaded list (which never carries a claim_url).
sealed class InvitationActionResult {
    data class Success(val invitation: Invitation, val deliveryStatus: String, val claimUrl: String?) : InvitationActionResult() {
        val emailSent: Boolean get() = deliveryStatus == InvitationDelivery.DELIVERY_SENT
        val deliveryFailed: Boolean get() = InvitationDelivery.isFailure(deliveryStatus)
    }
    data class Failure(val message: ErrorText) : InvitationActionResult()
}

sealed class RevokeInvitationResult {
    object Success : RevokeInvitationResult()
    data class Failure(val message: ErrorText) : RevokeInvitationResult()
}

// Backs InvitationsScreen, opened from a toolbar button on UsersScreen.
// 401 handling is centralized in ApiClient.onUnauthorized (fired
// synchronously before the ApiError is thrown on any request) -- this
// ViewModel has no sessionState param and does nothing special for 401
// beyond the normal ApiError catch below, matching every other ViewModel
// in this app.
class InvitationsViewModel : ViewModel() {
    private val surfaceEpoch = AtomicLong(0L)
    private val latestLoad = LatestRequestGate()
    private val _invitations = MutableStateFlow<List<Invitation>>(emptyList())
    val invitations: StateFlow<List<Invitation>> = _invitations

    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    private val _pendingActionResult = MutableStateFlow<InvitationActionResult.Success?>(null)
    val pendingActionResult: StateFlow<InvitationActionResult.Success?> = _pendingActionResult

    fun consumePendingActionResult() {
        _pendingActionResult.value = null
    }

    /** Invalidates in-flight actions and releases any one-time claim URL. */
    fun closeSurface() {
        surfaceEpoch.incrementAndGet()
        _pendingActionResult.value = null
    }

    /** Retain the result only while Android is recreating the same surface. */
    fun onSurfaceHidden(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) closeSurface()
    }

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val invitations: List<Invitation> = client.listInvitations()
            if (!latestLoad.isCurrent(generation)) return
            _invitations.value = invitations
            _errorMessage.value = null
        } catch (e: ApiError) {
            if (!latestLoad.isCurrent(generation)) return
            _errorMessage.value = e.toErrorText(R.string.error_could_not_load_invitations)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestLoad.isCurrent(generation)) return
            _errorMessage.value = ErrorText.Res(R.string.error_could_not_load_invitations)
        }
    }

    // Returns an explicit result rather than routing through _errorMessage,
    // for the same race/double-display reason UsersViewModel.createUser
    // does: the create dialog needs its own signal (and the claim_url/
    // email_sent payload) that a concurrent load() can't stomp, and a
    // validation failure should only render inline in the dialog.
    suspend fun send(email: String, role: String, note: String?, client: ApiClient): InvitationActionResult {
        val requestEpoch = surfaceEpoch.get()
        return try {
            val response = client.createInvitation(CreateInvitationRequest(email = email, role = role, note = note))
            // The 201 carries the only copy of the claim link: keep it before anything that can be cancelled.
            val result = InvitationActionResult.Success(response.invitation, response.delivery_status, response.claim_url)
            if (surfaceEpoch.get() == requestEpoch) _pendingActionResult.value = result
            load(client)
            result
        } catch (e: ApiError) {
            InvitationActionResult.Failure(e.toErrorText(R.string.error_could_not_send_invitation))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            InvitationActionResult.Failure(ErrorText.Res(R.string.error_could_not_send_invitation))
        }
    }

    suspend fun resend(invitation: Invitation, client: ApiClient): InvitationActionResult {
        val requestEpoch = surfaceEpoch.get()
        return try {
            val response = client.resendInvitation(invitation.id)
            // The 201 carries the only copy of the claim link: keep it before anything that can be cancelled.
            val result = InvitationActionResult.Success(response.invitation, response.delivery_status, response.claim_url)
            if (surfaceEpoch.get() == requestEpoch) _pendingActionResult.value = result
            load(client)
            result
        } catch (e: ApiError) {
            InvitationActionResult.Failure(e.toErrorText(R.string.error_could_not_resend_invitation))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            InvitationActionResult.Failure(ErrorText.Res(R.string.error_could_not_resend_invitation))
        }
    }

    suspend fun revoke(invitation: Invitation, client: ApiClient): RevokeInvitationResult {
        return try {
            client.revokeInvitation(invitation.id)
            load(client)
            RevokeInvitationResult.Success
        } catch (e: ApiError) {
            RevokeInvitationResult.Failure(e.toErrorText(R.string.error_could_not_revoke_invitation))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            RevokeInvitationResult.Failure(ErrorText.Res(R.string.error_could_not_revoke_invitation))
        }
    }

    override fun onCleared() {
        closeSurface()
        super.onCleared()
    }
}
