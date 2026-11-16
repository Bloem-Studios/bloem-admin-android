package com.bloemstudios.admin.links

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Put in a screen that owns a page choice. When a waiting link names this screen's server ([serverId], null for All
 * servers) it selects the link's page through [select] and marks the link handled. The shell routes to the screen first,
 * so this only runs once the app lock and the sign-in check have been passed.
 */
@Composable
fun DeepLinkPageEffect(serverId: String?, select: (destinationId: String) -> Unit) {
    val request by DeepLinkInbox.pending.collectAsState()
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        if (pending.target.serverId != serverId) return@LaunchedEffect
        if (DeepLinkInbox.isFresh(pending)) {
            val destination = pending.target.destinationId
            val item = pending.target.itemId
            if (destination != null && item != null) DeepLinkItems.offer(destination, item, pending.offeredAt)
            destination?.let(select)
        }
        DeepLinkInbox.consume(pending)
    }
}
