package com.bloemstudios.admin.invitations

import kotlinx.serialization.Serializable

// AdminInvitation from the v2 invitation list. Identifiers are opaque strings. id, email,
// delivery, role, library_ids, create_profile, show_tour, note, invited_by, invited_by_name, status,
// expires_at and created_at are always present; access_group_id, accepted_at and accepted_user_id
// are omitted when they do not apply. `email` is empty for a pending link invitation. `status` and
// `delivery` are rendered verbatim, never matched against a closed set.
@Serializable
data class Invitation(
    val id: String,
    val email: String,
    val delivery: String,
    val role: String,
    val access_group_id: String? = null,
    val library_ids: List<String>? = null,
    val create_profile: Boolean,
    val show_tour: Boolean,
    val note: String = "",
    val invited_by: String,
    val invited_by_name: String = "",
    val status: String,
    val expires_at: String,
    val accepted_at: String? = null,
    val accepted_user_id: String? = null,
    val created_at: String
)

// Exactly the fields the UI exposes for the v2 invitation create. `delivery` is left out so the
// server emails when it can and otherwise hands back the link; access_group_id, library_ids,
// create_profile and show_tour are left to the server's defaults.
@Serializable
data class CreateInvitationRequest(
    val email: String,
    val role: String,
    val note: String? = null
)

// InvitationDelivery: the response to create (201) and resend (201). claim_url is a one-time
// disclosure -- it is never returned again, so it is held in memory only until the admin dismisses it.
@Serializable
data class InvitationDelivery(
    val claim_url: String? = null,
    val delivery_status: String,
    val invitation: Invitation
) {
    /** `sent`: the server emailed the link. */
    val emailSent: Boolean get() = delivery_status == DELIVERY_SENT

    /**
     * The admin must not assume an email arrived. Documented values: `sent`, `not_configured` (no
     * email on this server), `not_requested` (link invitation) and `failed_or_unknown`. Only the
     * first three are understood non-failures; anything else, documented or not, is flagged.
     */
    val deliveryFailed: Boolean get() = isFailure(delivery_status)

    companion object {
        const val DELIVERY_SENT = "sent"
        const val DELIVERY_NOT_CONFIGURED = "not_configured"
        const val DELIVERY_NOT_REQUESTED = "not_requested"
        const val DELIVERY_FAILED_OR_UNKNOWN = "failed_or_unknown"
        fun isFailure(status: String) = status !in NON_FAILURE_STATUSES
        private val NON_FAILURE_STATUSES = setOf(DELIVERY_SENT, DELIVERY_NOT_CONFIGURED, DELIVERY_NOT_REQUESTED)
    }
}
