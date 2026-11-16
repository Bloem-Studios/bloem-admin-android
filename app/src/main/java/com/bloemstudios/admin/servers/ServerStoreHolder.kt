package com.bloemstudios.admin.servers

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One [ServerStore] per process, shared by the app's screens and the background check. Two stores would each hold
 * their own copy of a session, and a refresh token works once: the second store would then fail to refresh and the
 * admin would be signed out. The store opens on the first lease and closes with the last.
 *
 * The last lease closes the store while the holder's lock is held, so a store that is opening afterwards can never overlap
 * one that is still closing: two live stores would each spend the same single-use refresh token.
 *
 * Opening a store blocks on storage, so call [acquire] off the main thread.
 */
object ServerStoreHolder {
    private val lock = Any()
    private var store: ServerStore? = null
    private var leases = 0

    /** Test seam: builds the store on the first lease. */
    internal var factory: (Context) -> ServerStore = { ServerStore(it) }

    class Lease internal constructor(val store: ServerStore) : Closeable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) release(store)
        }
    }

    fun acquire(context: Context): Lease = synchronized(lock) {
        val current = store ?: factory(context.applicationContext).also { store = it }
        leases += 1
        Lease(current)
    }

    private fun release(released: ServerStore) = synchronized(lock) {
        leases -= 1
        if (leases == 0 && store === released) {
            store = null
            released.close()
        }
    }
}
