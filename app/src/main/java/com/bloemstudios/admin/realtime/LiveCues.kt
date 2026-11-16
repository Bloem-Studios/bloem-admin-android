package com.bloemstudios.admin.realtime

/**
 * Says that a server's events socket just reported a change on a channel. The socket is the app's own and is open only while the
 * app is in front; this is how the widgets hear of a change then, without ever opening a socket themselves. The app installs the
 * listener once (see `WidgetSync`); with none installed (unit tests, a process that only runs a worker) nothing happens.
 *
 * Only change events are announced, not the snapshot every connection starts with: that one is a cue to read, and the page that
 * opened the socket has just read.
 */
object LiveCues {
    /** [origin] is the server's address as [com.bloemstudios.admin.network.ServerOrigin.issuer]. */
    @Volatile
    var listener: ((origin: String, channel: String) -> Unit)? = null

    fun emit(origin: String, channel: String) {
        runCatching { listener?.invoke(origin, channel) }
    }
}
