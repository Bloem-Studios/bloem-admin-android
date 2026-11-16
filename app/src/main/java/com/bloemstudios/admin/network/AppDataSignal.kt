package com.bloemstudios.admin.network

/**
 * Says that the app just changed something a widget or the progress notification shows: a request was approved or declined,
 * a library scan or a backup was started. The API calls that do those things emit it after the server accepted them. The app
 * installs the listener once (see `App`); with none installed (unit tests, a process that only runs a worker) nothing happens.
 */
object AppDataSignal {
    sealed interface Event {
        /** The address of the server the client talks to, as [ServerOrigin.issuer]. */
        val origin: String
    }

    data class RequestDecided(override val origin: String) : Event
    data class ScanStarted(override val origin: String, val libraryId: String) : Event
    data class BackupStarted(override val origin: String) : Event

    /** Any write the server accepted (POST, PUT, PATCH, DELETE): something a widget shows may have changed. [path] is the route. */
    data class Wrote(override val origin: String, val path: String) : Event

    @Volatile
    var listener: ((Event) -> Unit)? = null

    fun emit(event: Event) {
        runCatching { listener?.invoke(event) }
    }
}
