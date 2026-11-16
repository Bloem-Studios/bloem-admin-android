package com.bloemstudios.admin.realtime

import androidx.compose.runtime.Composable
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer

/**
 * [ForegroundEffect] for a page that shows live data: [refresh] runs when the page opens and again whenever the server's
 * events socket says one of [channels] changed. There is no timer; see [watchLive] for the one exception (no socket).
 */
@Composable
fun LiveRefreshEffect(client: ApiClient, channels: Set<String>, vararg keys: Any?, refresh: suspend () -> Unit) {
    ForegroundEffect(client, channels, *keys) { watchLive(listOf(client.live), channels, refresh = refresh) }
}

/**
 * The all-servers form: one socket per server, each held only while this page is in front. A cue from any of them reads
 * everything again through [refresh], the way the page's own pull does. A server with no stored sign-in has no socket to
 * hold. With none at all the page just reads once.
 */
@Composable
fun LiveRefreshEffect(
    servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient, channels: Set<String>, vararg keys: Any?, refresh: suspend () -> Unit,
) {
    ForegroundEffect(servers, channels, *keys) {
        watchLive(servers.mapNotNull { runCatching { clientFor(it).live }.getOrNull() }, channels, refresh = refresh)
    }
}
