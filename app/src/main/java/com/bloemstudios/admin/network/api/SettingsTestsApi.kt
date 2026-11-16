package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A server notification channel (Discord or webhook), with what its last posts did. */
@Serializable
data class ServerChannel(
    val id: String,
    val name: String = "",
    val type: String = "",
    val enabled: Boolean = false,
    val consecutive_failures: Int = 0,
    val disabled_reason: String? = null,
    val last_success_at: String? = null,
    val last_failure_at: String? = null,
    val last_failure_message: String? = null,
)

suspend fun ApiClient.listServerChannels(): List<ServerChannel> = readAllPages("/api/v2/admin/notifications/server-channels")

@Serializable
data class ChannelTestResult(val ok: Boolean = false, val http_status: Int = 0, val duration_ms: Long = 0, val message: String = "")

/** Sends one sample message to the channel and waits for the answer. Never replayed automatically. */
suspend fun ApiClient.testServerChannel(id: String): ChannelTestResult =
    request(
        "/api/v2/admin/notifications/server-channels/${id.encodeURLPathPart()}/test",
        io.ktor.http.HttpMethod.Post, com.bloemstudios.admin.network.NoBody, replayUnauthorized = false,
    )

@Serializable
data class ExternalSignInCapabilities(val available: Boolean = false, val connection_test: Boolean = false)

suspend fun ApiClient.getExternalSignInCapabilities(): ExternalSignInCapabilities = get("/api/v2/auth/external-sign-in/capabilities")

@Serializable
data class PluginCapability(
    val type: String = "",
    val id: String = "",
    val display_name: String = "",
    val sign_in_mode: String? = null,
    val metadata: JsonElement? = null,
) {
    /** The plugin says it can test its connection. */
    val declaresConnectionTest: Boolean
        get() = (metadata as? JsonObject)?.get("connection_test")?.jsonPrimitive?.booleanOrNull == true
}

@Serializable
data class PluginAuthBinding(val capability_id: String = "", val enabled: Boolean = false)

@Serializable
data class AuthTestRequest(val capability_id: String)

@Serializable
data class AuthTestStep(val id: String = "", val label: String = "", val ok: Boolean = false, val message: String = "")

@Serializable
data class AuthTestResult(val ok: Boolean = false, val steps: List<AuthTestStep> = emptyList(), val callback_url: String = "")

/** Tests the provider with its saved settings. A failed check is a 200 with ok false; a plugin without a test is 409. */
suspend fun ApiClient.testSignInProvider(installationId: String, capabilityId: String): AuthTestResult =
    postOnce("/api/v2/admin/plugins/installations/${installationId.encodeURLPathPart()}/auth-binding/test", AuthTestRequest(capabilityId))

// Network access (Tailscale, NetBird): the providers an installed plugin offers, and each host's state.
@Serializable
data class NetworkProvider(val provider: String, val display_name: String = "")

@Serializable
data class NetworkAccessCapabilities(val available: Boolean = false, val providers: List<NetworkProvider> = emptyList())

suspend fun ApiClient.getNetworkAccessCapabilities(): NetworkAccessCapabilities = get("/api/v2/network-access/capabilities")

@Serializable
data class NetworkHostRef(val id: String, val role: String = "", val name: String = "")

@Serializable
data class NetworkHostStatus(
    val host: NetworkHostRef,
    val state: String = "",
    val hostname: String? = null,
    val origin: String? = null,
    val addresses: List<String> = emptyList(),
    val auth_url: String? = null,
    val error: String? = null,
    val updated_at: String? = null,
)

@Serializable
data class NetworkAccessStatus(val provider: String = "", val hosts: List<NetworkHostStatus> = emptyList())

suspend fun ApiClient.getNetworkAccessStatus(provider: String): NetworkAccessStatus =
    get("/api/v2/admin/network-access/${provider.encodeURLPathPart()}/status")

@Serializable
data class NetworkAccessCommand(val hosts: List<String>)

suspend fun ApiClient.connectNetworkAccess(provider: String, hostId: String): NetworkAccessStatus =
    post("/api/v2/admin/network-access/${provider.encodeURLPathPart()}/connect", NetworkAccessCommand(listOf(hostId)))

suspend fun ApiClient.disconnectNetworkAccess(provider: String, hostId: String): NetworkAccessStatus =
    post("/api/v2/admin/network-access/${provider.encodeURLPathPart()}/disconnect", NetworkAccessCommand(listOf(hostId)))
