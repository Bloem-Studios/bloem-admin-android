package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors

internal fun RealtimeState.labelResource(): Int = when (this) {
    RealtimeState.Connecting -> R.string.session_source_connecting
    RealtimeState.Live -> R.string.session_source_live
    RealtimeState.Reconnecting -> R.string.session_source_reconnecting
    RealtimeState.Unavailable -> R.string.session_source_unavailable
    RealtimeState.SignInRequired -> R.string.streams_server_sign_in_title
}

internal fun SessionFailure.messageResource(): Int = when (this) {
    SessionFailure.SignInRequired -> R.string.session_source_sign_in_message
    SessionFailure.Unsupported -> R.string.session_source_polling
    SessionFailure.Protocol -> R.string.session_source_protocol
    SessionFailure.Connection -> R.string.session_source_connection
    SessionFailure.Deadline -> R.string.session_source_deadline
    SessionFailure.AccessChanged -> R.string.session_source_connection
}

/**
 * What a page that has no sessions to show tells the person: what went wrong and what to do about it. The status
 * line says the same thing in the middle of a reconnect ("Reconnecting and checking sessions"), which is no help
 * when there is nothing on the page to go with it.
 */
internal fun SessionFailure.failureMessageResource(): Int = when (this) {
    SessionFailure.SignInRequired -> R.string.session_source_sign_in_message
    SessionFailure.Protocol -> R.string.session_failure_protocol
    SessionFailure.Deadline -> R.string.session_failure_deadline
    SessionFailure.Unsupported, SessionFailure.Connection, SessionFailure.AccessChanged -> R.string.session_failure_connection
}

/**
 * True when the read of the sessions failed and there is nothing to show. [SessionFailure.Unsupported] alone
 * does not count: it only says the live socket is not in use, and the page reads the list itself, so it is loading.
 */
internal fun hasNoSessionsBecauseOfFailure(sessions: List<*>?, error: SessionFailure?): Boolean =
    sessions == null && error != null && error != SessionFailure.Unsupported && error != SessionFailure.SignInRequired

/** One error: what went wrong, how to fix it, and Try again. It replaces the page's status and loading lines. */
@Composable
internal fun SessionsFailureSurface(error: SessionFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    VeilStateSurface(
        kind = VeilStateSurfaceKind.Failure,
        title = stringResource(R.string.streams_state_failure_title),
        message = stringResource(error.failureMessageResource()),
        stateDescription = stringResource(R.string.streams_state_failure_description),
        modifier = modifier,
        action = VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry),
    )
}

/** A dot and a word: the tally dot while the socket is live, a warning dot for anything else. */
@Composable
internal fun SessionSourceBadge(state: RealtimeState, modifier: Modifier = Modifier) {
    StatusLabel(
        stringResource(state.labelResource()),
        modifier = modifier,
        tone = if (state == RealtimeState.Live) StatusTone.Live else StatusTone.Warning,
        dot = true,
    )
}

/**
 * The one line under the badge, and only when it says something the badge does not. Rows on the page that may be old
 * say so; a page with no rows says what went wrong. Never both, and never both lines of a stale page.
 */
@Composable
internal fun SessionSourceNote(state: RealtimeState, error: SessionFailure?, hasSessions: Boolean) {
    if (hasSessions) {
        if (state == RealtimeState.Live) return
        if (error == SessionFailure.Unsupported) {
            Text(stringResource(SessionFailure.Unsupported.messageResource()), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        } else {
            Text(stringResource(R.string.status_sessions_stale), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
        }
    } else if (error != null) {
        Text(stringResource(error.messageResource()), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
}

@Composable
internal fun SessionSourceStatus(state: RealtimeState, error: SessionFailure?, hasSessions: Boolean) {
    Column {
        SessionSourceBadge(state)
        SessionSourceNote(state, error, hasSessions)
    }
}
