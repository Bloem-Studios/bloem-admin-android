package com.bloemstudios.admin.auth

import kotlinx.serialization.Serializable

@Serializable
data class ServerIdentity(val server_id: String)

@Serializable
data class AuthProvider(
    val id: String,
    val display_name: String,
    val mode: String,
    val default: Boolean,
    val installation_id: String? = null,
    val native_start_path: String? = null
)

/** An Authentik provider, by the name or id the server gives it. */
val AuthProvider.isAuthentik: Boolean
    get() = display_name.contains("authentik", ignoreCase = true) || id.contains("authentik", ignoreCase = true)

@Serializable
data class AuthProviders(val password_login: Boolean, val items: List<AuthProvider>) {
    /**
     * OAuth providers that offer native sign-in: Authentik first (the sign-in this app leads with, whatever order or
     * default the server lists), then the server's default, otherwise in server order.
     */
    val nativeOAuth: List<AuthProvider>
        get() = items.filter { it.mode == "oauth" && it.native_start_path != null }
            .sortedWith(compareByDescending<AuthProvider> { it.isAuthentik }.thenByDescending { it.default })

    /** [nativeOAuth] with [rememberedId] moved first when the server still offers it. */
    fun nativeOAuthFor(rememberedId: String?): List<AuthProvider> {
        val all = nativeOAuth
        val remembered = all.firstOrNull { it.id == rememberedId } ?: return all
        return listOf(remembered) + all.filterNot { it === remembered }
    }
}

@Serializable
data class OAuthCompleteRequest(val code: String, val code_verifier: String) {
    override fun toString(): String = "OAuthCompleteRequest(redacted)"
}

@Serializable
data class OAuthCompletion(val access_token: String, val refresh_token: String, val expires_in: Long) {
    override fun toString(): String = "OAuthCompletion(redacted)"
}
