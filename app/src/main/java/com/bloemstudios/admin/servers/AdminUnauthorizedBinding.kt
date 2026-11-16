package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import java.io.Closeable

/**
 * Binds one visible administrator session to one cached client without
 * allowing a late response from a displaced credential to expire the new
 * login. The rejected snapshot includes the store generation, so equality is
 * both token- and identity-sensitive without comparing bearer strings here.
 */
internal class AdminUnauthorizedBinding(
    private val client: ApiClient,
    currentCredential: () -> AccessTokenSnapshot,
    // A synchronous in-memory gate update only: no I/O, suspension or other locks.
    onCurrentUnauthorized: () -> Unit
) : Closeable {
    private val boundEpoch = client.currentPrincipalEpoch
    private var disposed = false
    // Terminal sessions authority applies to the principal, not one refreshed token.
    // Check and commit the gate together so replacement cannot interleave between them.
    private val terminalCallback: (Long) -> Unit = { rejectedEpoch ->
        synchronized(client.sessionAuthorityLock) {
            if (!disposed && !client.isClosed.value && rejectedEpoch == boundEpoch &&
                client.currentPrincipalEpoch == rejectedEpoch) {
                onCurrentUnauthorized()
            }
        }
    }

    private val callback: suspend (AccessTokenSnapshot) -> Unit = { rejected ->
        val current = currentCredential()
        // LoginRequired is reported only after ServerStore has durably
        // cleared the rejected credential, which advances its generation.
        // Accept that exact cleared successor as belonging to this failure,
        // while still rejecting a callback displaced by a newer login.
        val clearedByThisRecovery =
            current.token == null && current.generation == rejected.generation + 1L
        if (current == rejected || clearedByThisRecovery) onCurrentUnauthorized()
    }

    init {
        synchronized(client.sessionAuthorityLock) {
            client.onUnauthorized = callback
            client.onTerminalAuthorityLost = terminalCallback
        }
    }

    override fun close() {
        synchronized(client.sessionAuthorityLock) {
            disposed = true
            if (client.onUnauthorized === callback) client.onUnauthorized = null
            if (client.onTerminalAuthorityLost === terminalCallback) client.onTerminalAuthorityLost = null
        }
    }
}
