package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.CommandIdentity
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

enum class SessionCommand(val path: String) { PAUSE("pause"), RESUME("resume"), STOP("stop") }

@Serializable
private data class TerminateBody(val reason: String? = null)

@Serializable
private data class MessageBody(val title: String? = null, val message: String, val command_id: String, val sequence: Long)

private fun sessionPath(sessionId: String, action: String) =
    "/api/v2/admin/sessions/${sessionId.encodeURLPathPart()}/$action"

/** Every page of the live session list. */
suspend fun ApiClient.listSessions(): List<AdminSession> = readAllPages("/api/v2/admin/sessions")

/** Sent once: a failed command is not replayed, including after a 401. The receipt body is not needed. */
suspend fun ApiClient.sendSessionCommand(sessionId: String, command: SessionCommand, identity: CommandIdentity) {
    postOnce<Unit, CommandIdentity>(sessionPath(sessionId, command.path), identity)
}

/** Ends the session and revokes its playback authority. */
suspend fun ApiClient.terminateSession(sessionId: String, reason: String?) {
    postOnce<Unit, TerminateBody>(sessionPath(sessionId, "terminate"), TerminateBody(reason))
}

suspend fun ApiClient.messageSession(sessionId: String, title: String?, message: String, identity: CommandIdentity) {
    postOnce<Unit, MessageBody>(
        sessionPath(sessionId, "message"),
        MessageBody(title, message, identity.command_id, identity.sequence)
    )
}
