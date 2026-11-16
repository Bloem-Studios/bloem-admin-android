package com.bloemstudios.admin.streams

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.SessionCommand
import com.bloemstudios.admin.network.api.messageSession
import com.bloemstudios.admin.network.api.sendSessionCommand
import com.bloemstudios.admin.network.api.terminateSession
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// sendMessage() gets its own explicit result rather than routing through
// the shared _errorMessage flow (like pause/resume/stop/terminate's act()
// does): the Message dialog needs to render a specific "client not
// connected" state (409 -- an expected condition, not a bug) distinct from
// a generic failure, plus a success confirmation, and a shared StateFlow
// that's also being overwritten by the poll loop's load() is the wrong
// place for that (same reasoning as CreateUserResult/UpdateUserResult in
// UsersViewModel).
sealed class SendMessageResult {
    object Success : SendMessageResult()
    object Busy : SendMessageResult()
    object ClientNotConnected : SendMessageResult()
    data class Failure(val message: ErrorText) : SendMessageResult()
}

class StreamsViewModel(private val sequencer: PlaybackCommandSequencer = PlaybackCommandSequencer()) : ViewModel() {
    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    // Reconciliation is owned/coalesced by the foreground repository.
    suspend fun load(client: ApiClient) { client.liveSessions.refresh() }

    suspend fun pause(session: AdminSession, client: ApiClient) = act(session, client) { id ->
        sendSessionCommand(session.session_id, SessionCommand.PAUSE, id)
    }
    suspend fun resume(session: AdminSession, client: ApiClient) = act(session, client) { id ->
        sendSessionCommand(session.session_id, SessionCommand.RESUME, id)
    }
    suspend fun stop(session: AdminSession, client: ApiClient) = act(session, client) { id ->
        sendSessionCommand(session.session_id, SessionCommand.STOP, id)
    }
    suspend fun terminate(session: AdminSession, client: ApiClient) = act(session, client) { terminateSession(session.session_id, null) }

    // A failed command is not retried: the next tap allocates a new identity.
    private suspend fun act(session: AdminSession, client: ApiClient, send: suspend ApiClient.(CommandIdentity) -> Unit): ActionResult =
        operations.runResult(session.session_id, ActionResult.Busy) {
            val generation = feedback.begin()
            val result = try {
                client.send(sequencer.next(session.session_id))
                feedback.publish(generation, null)
                ActionResult.Success
            } catch (e: ApiError) {
                feedback.publish(generation, commandErrorText(e, session.session_id))
                ActionResult.Failure
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                feedback.publish(generation, ErrorText.Res(R.string.error_action_failed))
                ActionResult.Failure
            }
            if (result == ActionResult.Success) client.liveSessions.reconcileAfterMutation()
            result
        }

    private fun commandErrorText(e: ApiError, key: String): ErrorText {
        if (e.statusCode == 409 && e.latestSequence != null) {
            sequencer.observe(key, e.latestSequence)
            return ErrorText.Res(R.string.error_stream_command_stale)
        }
        return e.toErrorText(R.string.error_action_failed)
    }

    // Doesn't reload sessions afterward (unlike act() above) -- a message
    // doesn't change any session's state, so there's nothing new to fetch.
    suspend fun sendMessage(session: AdminSession, title: String?, message: String, client: ApiClient): SendMessageResult {
        return operations.runResult(session.session_id, SendMessageResult.Busy) { try {
            client.messageSession(
                session.session_id, title?.takeIf { it.isNotBlank() }, message, sequencer.next(session.session_id)
            )
            SendMessageResult.Success
        } catch (e: ApiError) {
            // 409 (realtime_unavailable) means the client isn't connected in
            // real time to receive the message right now -- an expected,
            // routine condition (the stream itself is fine), not a bug --
            // so it gets its own case instead of the generic failure text.
            if (e.statusCode == 409 && e.latestSequence != null) {
                sequencer.observe(session.session_id, e.latestSequence)
                SendMessageResult.Failure(ErrorText.Res(R.string.error_stream_command_stale))
            } else if (e.statusCode == 409) {
                SendMessageResult.ClientNotConnected
            } else {
                SendMessageResult.Failure(e.toErrorText(R.string.error_action_failed))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SendMessageResult.Failure(ErrorText.Res(R.string.error_action_failed))
        } }
    }
}
