package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.invitations.CreateInvitationRequest
import com.bloemstudios.admin.invitations.Invitation
import com.bloemstudios.admin.invitations.InvitationDelivery
import com.bloemstudios.admin.network.ApiClient
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject

private fun invitationPath(id: String, suffix: String = "") = "/api/v2/admin/invitations/${id.encodeURLPathPart()}$suffix"

/** Every page of the invitation list. */
suspend fun ApiClient.listInvitations(): List<Invitation> = readAllPages("/api/v2/admin/invitations")

/** One submission: the response carries the only copy of the claim link, so an uncertain create is never replayed. */
suspend fun ApiClient.createInvitation(body: CreateInvitationRequest): InvitationDelivery =
    postOnce("/api/v2/admin/invitations", body)

/** Replaces the claim link and, by default, emails it. Submitted once for the same reason as [createInvitation]. */
suspend fun ApiClient.resendInvitation(id: String): InvitationDelivery =
    postOnce(invitationPath(id, "/resend"), JsonObject(emptyMap()))

/** DELETE needs neither a body nor a precondition and answers 204. */
suspend fun ApiClient.revokeInvitation(id: String) = delete(invitationPath(id))
