package com.bloemstudios.admin.auth

/**
 * Process-wide, single-slot holder for the OAuth redirect URI. The URI carries a one-time code and
 * the state, so it lives only in memory and is never logged or put in an Intent.
 */
object OAuthRedirects {
    private val lock = Any()
    private var waiting = false
    private var delivery: String? = null

    /** A sign-in starts waiting; any older delivery is discarded. */
    fun begin() = synchronized(lock) { waiting = true; delivery = null }

    /** Stores the redirect. It belongs to the waiting sign-in if one is waiting, else it is an orphan. */
    fun deliver(uri: String) = synchronized(lock) { delivery = uri }

    /** Returns and clears the waiting sign-in's delivery, or null when none arrived. */
    fun takeForSignIn(): String? = synchronized(lock) {
        if (!waiting) return null
        delivery.also { delivery = null }
    }

    /**
     * The sign-in finished or was cancelled; stops waiting. A delivery nobody took stays as an orphan
     * (it is never redeemed, but the person is told the sign-in was interrupted); [begin] discards it.
     */
    fun end() = synchronized(lock) { waiting = false }

    /** Returns and clears a delivery that arrived while no sign-in was waiting. */
    fun takeOrphan(): String? = synchronized(lock) {
        if (waiting) return null
        delivery.also { delivery = null }
    }
}
