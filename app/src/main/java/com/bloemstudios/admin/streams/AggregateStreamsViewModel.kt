package com.bloemstudios.admin.streams

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.SessionCommand
import com.bloemstudios.admin.network.api.messageSession
import com.bloemstudios.admin.network.api.sendSessionCommand
import com.bloemstudios.admin.network.api.terminateSession
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerRequestLocks
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

/** Aggregate controls reconcile the shared repository of only the affected server. */
class AggregateStreamsViewModel(private val sequencer: PlaybackCommandSequencer = PlaybackCommandSequencer()) : ViewModel() {
    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    private val locks = ServerRequestLocks()
    // Keyed by server and session so two servers' session ids cannot share a sequence.

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) = kotlinx.coroutines.coroutineScope {
        servers.forEach { server -> launch {
            try { clientFor(server).liveSessions.refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Each section renders its unavailable source independently. */ }
        } }
    }

    suspend fun pause(server: AdminServer, session: AdminSession, clientFor: (AdminServer) -> ApiClient) =
        act(server, session, clientFor) { id -> sendSessionCommand(session.session_id, SessionCommand.PAUSE, id) }

    suspend fun resume(server: AdminServer, session: AdminSession, clientFor: (AdminServer) -> ApiClient) =
        act(server, session, clientFor) { id -> sendSessionCommand(session.session_id, SessionCommand.RESUME, id) }

    suspend fun stop(server: AdminServer, session: AdminSession, clientFor: (AdminServer) -> ApiClient) =
        act(server, session, clientFor) { id -> sendSessionCommand(session.session_id, SessionCommand.STOP, id) }

    suspend fun terminate(server: AdminServer, session: AdminSession, clientFor: (AdminServer) -> ApiClient) =
        act(server, session, clientFor) { terminateSession(session.session_id, null) }

    /**
     * Sends [message] to the client playing [session] on [server]. Like the single-server version it does not re-read
     * the session list (a message changes no session), and a failure names the server it came from.
     */
    suspend fun sendMessage(server: AdminServer, session: AdminSession, title: String?, message: String,
        clientFor: (AdminServer) -> ApiClient): SendMessageResult =
        operations.runResult(pendingKey(server, session), SendMessageResult.Busy) {
            locks.forServer(server).withLock {
                try {
                    clientFor(server).messageSession(
                        session.session_id, title?.takeIf { it.isNotBlank() }, message, sequencer.next(pendingKey(server, session))
                    )
                    SendMessageResult.Success
                } catch (e: CancellationException) { throw e }
                catch (e: ApiError) {
                    if (e.statusCode == 409 && e.latestSequence != null) {
                        sequencer.observe(pendingKey(server, session), e.latestSequence)
                        SendMessageResult.Failure(ErrorText.Prefixed(server.displayName, ErrorText.Res(R.string.error_stream_command_stale)))
                    } else if (e.statusCode == 409) {
                        // The client is not connected in real time to receive it: routine, not a fault.
                        SendMessageResult.ClientNotConnected
                    } else SendMessageResult.Failure(e.toErrorText(server.displayName, R.string.error_action_failed))
                } catch (_: Exception) {
                    SendMessageResult.Failure(ErrorText.Prefixed(server.displayName, ErrorText.Res(R.string.error_action_failed)))
                }
            }
        }

    fun pendingKey(server: AdminServer, session: AdminSession) = "${server.id}:${session.session_id}"

    private suspend fun act(server: AdminServer, session: AdminSession, clientFor: (AdminServer) -> ApiClient,
        send: suspend ApiClient.(CommandIdentity) -> Unit): ActionResult =
        operations.runResult(pendingKey(server, session), ActionResult.Busy) {
            val generation = feedback.begin()
            locks.forServer(server).withLock {
                var client: ApiClient? = null
                val result = try {
                    client = clientFor(server)
                    client.send(sequencer.next(pendingKey(server, session)))
                    feedback.publish(generation, null)
                    ActionResult.Success
                } catch (e: CancellationException) { throw e }
                catch (e: ApiError) {
                    if (e.statusCode == 409 && e.latestSequence != null) sequencer.observe(pendingKey(server, session), e.latestSequence)
                    val stale = e.statusCode == 409 && e.latestSequence != null
                    feedback.publish(generation,
                        if (stale) ErrorText.Prefixed(server.displayName, ErrorText.Res(R.string.error_stream_command_stale))
                        else e.toErrorText(server.displayName, R.string.error_action_failed))
                    ActionResult.Failure
                } catch (_: Exception) {
                    feedback.publish(generation, ErrorText.Res(R.string.error_action_failed))
                    ActionResult.Failure
                }
                if (result == ActionResult.Success) client?.liveSessions?.reconcileAfterMutation()
                else client?.liveSessions?.refresh()
                result
            }
        }

    fun clearErrorMessage() = dismissActionError()

    /** Retries just [server]'s section (e.g. after a transient network failure) without touching any other section. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadOneLocked(server, clientFor(server))
    }

    private suspend fun reloadOneLocked(server: AdminServer, client: ApiClient) { client.liveSessions.refresh() }
}
