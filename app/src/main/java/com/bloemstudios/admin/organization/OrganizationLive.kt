package com.bloemstudios.admin.organization

import androidx.compose.runtime.Composable
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.LiveConnection
import com.bloemstudios.admin.realtime.watch
import kotlinx.coroutines.awaitCancellation

/**
 * Keeps an organization page current. With the organization live socket ([OrganizationSession.liveOffered]) the page reads
 * when it opens and again each time the socket says its channel changed; there is no timer. The socket opens only while a
 * page that shows a channel is in front and closes shortly after the last one leaves.
 *
 * Without it (a server that has not got the feature, or a channel it does not serve) the page does what it did before the
 * socket: it reads when it opens and on pull, and nothing else. The one exception is the same for every screen of the app:
 * with the socket down for three attempts in a row the visible page reads once a minute until it is back.
 */
internal suspend fun watchOrganization(
    offered: Boolean,
    live: () -> LiveConnection,
    channels: Set<String>,
    refresh: suspend () -> Unit,
) {
    if (offered) live().watch(channels, pollWhenNotOffered = false, refresh = refresh)
    else {
        refresh()
        awaitCancellation()
    }
}

internal suspend fun OrganizationSession.watch(channels: Set<String>, refresh: suspend () -> Unit) =
    watchOrganization(liveOffered(), { live }, channels, refresh)

/** [ForegroundEffect] for an organization page that shows [channel]: [refresh] runs on open and whenever the channel changes. */
@Composable
internal fun OrganizationLiveEffect(session: OrganizationSession, channel: String, refresh: suspend () -> Unit) {
    ForegroundEffect(session, channel) { session.watch(setOf(channel), refresh) }
}
