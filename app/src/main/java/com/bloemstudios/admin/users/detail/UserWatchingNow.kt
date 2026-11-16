package com.bloemstudios.admin.users.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.progressFraction
import com.bloemstudios.admin.streams.streamClientLine
import com.bloemstudios.admin.streams.streamTitle
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

const val USER_WATCHING_NOW_TAG = "user-watching-now"

/** Whole minutes, never negative: the web's `minutes()` in its Watching now card. */
internal fun wholeMinutes(seconds: Double?): Int = ((seconds ?: 0.0) / 60.0).toInt().coerceAtLeast(0)

/** "12 of 45 min"; null when the file's length is not known. */
internal fun positionLabelMinutes(session: AdminSession): Pair<Int, Int>? {
    val duration = session.file_duration?.takeIf { it > 0 } ?: return null
    return wholeMinutes(session.position_seconds) to Math.round(duration / 60.0).toInt()
}

/**
 * What the account is playing right now, from `GET /api/v2/admin/sessions?user_id=`: title, profile and client, how far
 * in it is, and whether it plays or waits. Shown only while the account plays something, as on the web.
 */
@Composable
internal fun UserWatchingNowCard(sessions: List<AdminSession>) {
    if (sessions.isEmpty()) return
    DetailCard(stringResource(R.string.user_watching_now), Modifier.testTag(USER_WATCHING_NOW_TAG)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            sessions.forEach { session ->
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                    Text(streamTitle(session), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                    StatusLabel(
                        stringResource(if (session.is_paused) R.string.user_watching_paused else R.string.user_watching_playing),
                        tone = if (session.is_paused) StatusTone.Warning else StatusTone.Live,
                        dot = true,
                    )
                    val meta = listOfNotNull(
                        session.profile_name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.user_watching_profile, it) },
                        streamClientLine(session),
                        session.client_ip?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    progressFraction(session.position_seconds, session.file_duration)?.let { fraction ->
                        // The text below says the same, so the bar stays out of the reading order.
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.widthIn(max = 208.dp).fillMaxWidth().clearAndSetSemantics { },
                            color = VeilColors.Accent, trackColor = VeilColors.Hairline,
                        )
                    }
                    positionLabelMinutes(session)?.let { (at, of) ->
                        Text(stringResource(R.string.user_watching_position, at, of), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                }
            }
        }
    }
}
