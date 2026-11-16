package com.bloemstudios.admin.platform

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * Whether one server is a Bloem server, found out once and kept for the session.
 *
 * The app works with every Silo server. What only a Bloem server has (organizations, platform administration, support
 * access, everything under `/api/bloem/`) is offered only where `GET /api/bloem/v2/capabilities` answers with a capability
 * document. A plain Silo server answers 404 (or something that is not a capability document); that answer is final for the
 * session, and nothing under `/api/bloem/` is sent to that server again. An answer that says nothing about the server
 * (offline, a timeout, a 5xx) is not remembered: the extras stay out of sight and the next question asks again.
 */
class BloemDetector(private val fetch: suspend () -> PlatformCapabilities) {
    private val lock = Mutex()

    /** Null until a final answer arrives. [Verdict.capabilities] is null for a plain Silo server. */
    @Volatile
    private var verdict: Verdict? = null

    private class Verdict(val capabilities: PlatformCapabilities?)

    /** The server's capability document when it is a Bloem server, otherwise null. */
    suspend fun capabilities(): PlatformCapabilities? {
        verdict?.let { return it.capabilities }
        return lock.withLock {
            verdict?.let { return@withLock it.capabilities }
            try {
                fetch().also { verdict = Verdict(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiError) {
                if (error.statusCode in NOT_BLOEM_STATUSES) verdict = Verdict(null)
                null
            } catch (error: Exception) {
                // A page that is not a capability document (a proxy's HTML) is a server without the route.
                if (error is SerializationException || error is ContentConvertException || error is NoTransformationFoundException) {
                    verdict = Verdict(null)
                }
                null
            }
        }
    }

    suspend fun isBloem(): Boolean = capabilities() != null

    /** True once the server has been found to be plain Silo, without asking it anything. */
    val knownPlainSilo: Boolean get() = verdict?.let { it.capabilities == null } == true

    private companion object {
        /** A server without the route: not found, method not allowed or gone. */
        val NOT_BLOEM_STATUSES = setOf(404, 405, 410)
    }
}

/**
 * Asks this server once whether it is a Bloem server and keeps the answer. The Bloem pages, the organizations lookup and the
 * support access check all go through here, so a plain Silo server is asked a single question and never sees another
 * `/api/bloem/` route.
 */
val ApiClient.bloem: BloemDetector get() = bloemDetector

/** Whether this server is a Bloem server, asked once and kept for the session. */
suspend fun ApiClient.isBloemServer(): Boolean = bloem.isBloem()
