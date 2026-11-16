package com.bloemstudios.admin.alerts

import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.healthUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GET /api/v1/health, the public route every Bloem server answers without a sign-in. It uses its own bare client:
 * no stored token, no cookie, no header of ours, so a background check can never leak or refresh a session.
 *
 * Any answer below 500 means the server is there (a proxy's 404, a rate limit's 429 included): the alert is about a
 * server that stopped answering, not about a route that moved. A refused connection, a timeout, a certificate
 * problem or a 5xx from the server or its proxy counts as not answering.
 */
class HttpHealthProbe(
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val engineFactory: () -> HttpClientEngine = { CIO.create() },
) : HealthProbe {
    override suspend fun isReachable(baseUrl: String): Boolean {
        val url = try {
            ServerOrigin.parse(baseUrl).healthUrl()
        } catch (_: InvalidServerOrigin) {
            return false
        }
        val client = HttpClient(engineFactory()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { client.get(url).status.value < 500 } ?: false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        } finally {
            client.close()
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
