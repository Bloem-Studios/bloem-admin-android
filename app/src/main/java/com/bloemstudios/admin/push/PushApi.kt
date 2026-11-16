package com.bloemstudios.admin.push

import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.PushPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PushRegisterRequest(
    @SerialName("installation_id") val installationId: String,
    val platform: String,
    val token: String,
    /** Kinds this device has switched off. Sent so a reinstall or a second sign-in keeps the choices. */
    @SerialName("disabled_kinds") val disabledKinds: List<String>? = null,
    /** The widget report, sent only to a server that lists the widget wakes token. */
    @SerialName("widgets_placed") val widgetsPlaced: Boolean? = null,
    @SerialName("widget_channels") val widgetChannels: List<String>? = null,
)

@Serializable
data class PushKindState(val kind: String, val enabled: Boolean, @SerialName("server_emitted") val serverEmitted: Boolean = false)

@Serializable
data class PushRegistered(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_token") val displayToken: String,
    val kinds: List<PushKindState> = emptyList(),
    @SerialName("widgets_placed") val widgetsPlaced: Boolean = false,
    @SerialName("widget_channels") val widgetChannels: List<String> = emptyList(),
)

@Serializable
data class PushWidgetsRequest(@SerialName("widgets_placed") val widgetsPlaced: Boolean, val channels: List<String>)

@Serializable
data class PushWidgetsResponse(
    @SerialName("widgets_placed") val widgetsPlaced: Boolean = false,
    val channels: List<String> = emptyList(),
)

@Serializable
data class PushKindsRequest(val kinds: Map<String, Boolean>)

@Serializable
data class PushKindsResponse(val kinds: List<PushKindState> = emptyList())

/**
 * The two calls that use the device's display token instead of a sign-in. They use their own bare client: the token opens
 * nothing but this device's own notification text and its retire call, and a push must be readable while the app is locked
 * and its sign-in cannot be opened.
 */
class PushContentClient(
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val engineFactory: () -> HttpClientEngine = { CIO.create() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The notification text for [contentId] (`{deviceId}.{delivery}`), or null when the server says no or does not answer. */
    suspend fun fetch(baseUrl: String, displayToken: String, contentId: String): PushContent? {
        val url = urlFor(baseUrl, PushPaths.content(contentId)) ?: return null
        return call { client ->
            val response = client.get(url) { header(HttpHeaders.Authorization, "Bearer $displayToken") }
            if (!response.status.isSuccess()) return@call null
            json.decodeFromString(PushContent.serializer(), response.bodyAsText())
        }
    }

    /** Stops pushes to [deviceId]. True when the server removed it (or no longer knows it); never throws. */
    suspend fun retire(baseUrl: String, displayToken: String, deviceId: String): Boolean {
        val url = urlFor(baseUrl, PushPaths.retire(deviceId)) ?: return false
        return call { client ->
            val response = client.post(url) { header(HttpHeaders.Authorization, "Bearer $displayToken") }
            response.status.isSuccess() || response.status.value == 401
        } ?: false
    }

    private fun urlFor(baseUrl: String, path: String) = try {
        ServerOrigin.parse(baseUrl).resolve(path)
    } catch (_: InvalidServerOrigin) {
        null
    }

    private suspend fun <T> call(block: suspend (HttpClient) -> T?): T? {
        val client = HttpClient(engineFactory()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { block(client) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
