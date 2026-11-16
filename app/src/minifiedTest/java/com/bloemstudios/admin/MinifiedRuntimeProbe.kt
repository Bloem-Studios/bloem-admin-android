package com.bloemstudios.admin

import com.bloemstudios.admin.invitations.InvitationDelivery
import com.bloemstudios.admin.serverconfig.ServerStatus
import kotlinx.serialization.json.Json

/** Target-process contract probe retained only by the minified test variant. */
object MinifiedRuntimeProbe {
    fun verifySerializedContracts(): Boolean {
        val status = Json.decodeFromString<ServerStatus>(
            """{"started_at":"2026-08-29T00:00:00Z","restart_required":false,"restart_requested":false}"""
        )
        val invitation = Json.decodeFromString<InvitationDelivery>(
            """{"invitation":{"id":"1","email":"smoke@example.test","delivery":"email_sent","role":"user","library_ids":null,"create_profile":true,"show_tour":true,"note":"","invited_by":"1","invited_by_name":"Admin","status":"pending","expires_at":"2026-09-01T00:00:00Z","created_at":"2026-08-29T00:00:00Z"},"claim_url":"https://example.test/claim/one-time","delivery_status":"sent"}"""
        )
        return status.started_at == "2026-08-29T00:00:00Z" && invitation.claim_url != null
    }
}
