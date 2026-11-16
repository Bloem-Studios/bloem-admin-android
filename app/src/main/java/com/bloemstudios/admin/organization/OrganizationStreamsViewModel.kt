package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listSupportStreams
import com.bloemstudios.admin.network.api.messageSupportStream
import com.bloemstudios.admin.network.api.stopSupportStream
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.PlaybackCommandSequencer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The organization's stream as the Now Playing row draws it. The server never sends a client address, node or routing for an
 * organization, so none can reach the row: those fields stay empty and the row is told not to show them.
 */
fun SupportStream.toAdminSession(): AdminSession {
    val isEpisode = !series_name.isNullOrBlank()
    return AdminSession(
        session_id = session_id, user_id = account_id.toString(), username = person_name, profile_id = profile_id,
        // The server sends the episode's own name as the title and the show beside it.
        media_title = title, media_type = media_type, play_method = play_method,
        position_seconds = position_seconds, file_duration = duration_seconds?.toDouble(), is_paused = is_paused,
        profile_name = profile_name.ifBlank { null }, device_platform = device.platform.ifBlank { null },
        client_name = device.app.ifBlank { null }, client_label = device.app.ifBlank { null },
        client_label_full = listOf(device.app, device.name).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty { null },
        video_decision = video_decision.ifBlank { null }, audio_decision = audio_decision.ifBlank { null },
        effective_play_method = play_method.ifBlank { null },
        series_name = series_name?.takeIf { it.isNotBlank() }, episode_name = if (isEpisode) title else null,
        season_number = season_number, episode_number = episode_number,
        started_at = started_at.ifBlank { null }, updated_at = updated_at.ifBlank { null },
        client_ip = null,
    )
}

data class OrganizationStreamsState(
    val loading: Boolean = true,
    val streams: List<SupportStream> = emptyList(),
    val error: ErrorText? = null,
    val notice: ErrorText? = null,
    /** Streams a stop or message is being sent for. */
    val pendingIds: Set<String> = emptySet(),
    /** More streams than one read holds: the first ones are shown. */
    val truncated: Boolean = false,
    /** The server cannot list streams for an organization (capability false, or an older server). */
    val unavailable: Boolean = false,
)

sealed interface StreamMessageResult {
    /** Sent. [queued] is true when the player was not connected and the server will show it when it is. */
    data class Sent(val queued: Boolean) : StreamMessageResult
    data object Busy : StreamMessageResult
    data object ClientNotConnected : StreamMessageResult
    data class Invalid(val message: ErrorText) : StreamMessageResult
    data class Failed(val message: ErrorText) : StreamMessageResult
}

/**
 * The organization's live streams, with stop and message. The list is polled by the page (the server has no organization
 * realtime channel). A stop or message is sent once; a message keeps Silo's command identity, and after the server could not
 * confirm it ("may have been applied") the same text is sent again under the same identity, so it shows at most once.
 */
class OrganizationStreamsViewModel(
    private val sequencer: PlaybackCommandSequencer = PlaybackCommandSequencer(),
) : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(OrganizationStreamsState())
    val state: StateFlow<OrganizationStreamsState> = _state

    private data class Attempt(val title: String?, val message: String, val commandId: String, val sequence: Long)

    private val unconfirmed = HashMap<String, Attempt>()
    private val inFlight = HashSet<String>()

    suspend fun load(session: OrganizationSession) {
        val request = gate.begin()
        session.loadSupportCapabilities()
        if (!session.supportCapabilities.value.streams.list) {
            if (gate.isCurrent(request)) _state.update { it.copy(loading = false, unavailable = true, streams = emptyList()) }
            return
        }
        _state.update { it.copy(loading = it.streams.isEmpty(), error = null, unavailable = false) }
        val outcome = session.attempt(R.string.organization_streams_error) {
            val all = mutableListOf<SupportStream>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = session.client.listSupportStreams(cursor, PAGE_SIZE)
                all += page.streams
                cursor = page.next_cursor?.takeIf { it.isNotBlank() }
                pages += 1
            } while (cursor != null && pages < MAX_PAGES)
            all to (cursor != null)
        }
        if (!gate.isCurrent(request)) return
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(loading = false, streams = outcome.value.first, truncated = outcome.value.second, error = null)
                is Outcome.Failed -> it.copy(loading = false, error = outcome.message)
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    private fun pending(id: String, on: Boolean) = _state.update { it.copy(pendingIds = if (on) it.pendingIds + id else it.pendingIds - id) }

    /** Ends the stream. The server refuses the administrator's own stream and a platform account's; `can_stop` hides those. */
    suspend fun stop(session: OrganizationSession, stream: SupportStream): Boolean {
        if (!session.supportCapabilities.value.streams.stop || !stream.can_stop) return false
        if (!inFlight.add(stream.session_id)) return false
        pending(stream.session_id, true)
        _state.update { it.copy(error = null, notice = null) }
        val outcome = try {
            session.attempt(R.string.organization_streams_error_stop) { session.write { client, _ -> client.stopSupportStream(stream.session_id) } }
        } finally {
            inFlight.remove(stream.session_id)
            pending(stream.session_id, false)
        }
        return when (outcome) {
            is Outcome.Ok -> {
                _state.update {
                    it.copy(
                        notice = ErrorText.Res(if (outcome.value.client_notified) R.string.organization_streams_notice_stopped else R.string.organization_streams_notice_stopped_quietly),
                        streams = it.streams.filterNot { s -> s.session_id == stream.session_id },
                    )
                }
                load(session)
                true
            }
            is Outcome.Failed -> {
                // Read the list first (it may have changed under us), then say why the stop did not happen.
                load(session)
                _state.update { it.copy(error = outcome.message) }
                false
            }
        }
    }

    /** Puts a message on the stream's screen. A title is optional (up to 256 characters); the message is 1 to 2048. */
    suspend fun sendMessage(session: OrganizationSession, stream: SupportStream, title: String?, message: String): StreamMessageResult {
        if (!session.supportCapabilities.value.streams.message || !stream.can_message) return StreamMessageResult.Failed(ErrorText.Res(R.string.organization_streams_error_message))
        val text = message.trim()
        val heading = title?.trim()?.ifEmpty { null }
        if (text.isEmpty()) return StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_message_empty))
        if (text.codePointCount(0, text.length) > MESSAGE_LIMIT) return StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_message_too_long))
        if (heading != null && heading.codePointCount(0, heading.length) > TITLE_LIMIT) return StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_title_too_long))
        val id = stream.session_id
        if (!inFlight.add(id)) return StreamMessageResult.Busy
        pending(id, true)
        try {
            // The same text after an unconfirmed send is the same command; anything else is a new one.
            val attempt = unconfirmed[id]?.takeIf { it.title == heading && it.message == text }
                ?: sequencer.next(id).let { Attempt(heading, text, it.command_id, it.sequence) }
            return try {
                val receipt = session.write { client, _ -> client.messageSupportStream(id, heading, text, attempt.commandId, attempt.sequence) }
                unconfirmed.remove(id)
                StreamMessageResult.Sent(queued = receipt.delivery == "fallback_scheduled")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiError) {
                unconfirmed.remove(id)
                when {
                    error.statusCode == 409 && error.code == "realtime_unavailable" -> StreamMessageResult.ClientNotConnected
                    error.statusCode == 409 && error.latestSequence != null -> {
                        // The stream moved on: ask again above the latest applied command.
                        sequencer.observe(id, error.latestSequence)
                        StreamMessageResult.Failed(ErrorText.Res(R.string.error_stream_command_stale))
                    }
                    error.statusCode == 409 && error.code == "stale_sequence" -> StreamMessageResult.Failed(ErrorText.Res(R.string.error_stream_command_stale))
                    error.statusCode == 503 && error.code == "dependency_unavailable" -> {
                        // The command may have been applied: retry with the same identity.
                        unconfirmed[id] = attempt
                        StreamMessageResult.Failed(session.failureText(error, R.string.organization_streams_error_message))
                    }
                    else -> StreamMessageResult.Failed(session.failureText(error, R.string.organization_streams_error_message))
                }
            } catch (error: OrganizationStateChanged) {
                unconfirmed.remove(id)
                StreamMessageResult.Failed(session.failureText(error, R.string.organization_streams_error_message))
            } catch (error: OrganizationAccessDenied) {
                StreamMessageResult.Failed(session.failureText(error, R.string.organization_streams_error_message))
            } catch (_: Exception) {
                // Nothing came back: it may have been applied, so a retry of the same text keeps the identity.
                unconfirmed[id] = attempt
                StreamMessageResult.Failed(ErrorText.Res(R.string.organization_streams_error_message))
            }
        } finally {
            inFlight.remove(id)
            pending(id, false)
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5
        const val MESSAGE_LIMIT = 2048
        const val TITLE_LIMIT = 256
    }
}
