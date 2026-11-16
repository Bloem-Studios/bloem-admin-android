package com.bloemstudios.admin.servers

import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.OriginProblem
import com.bloemstudios.admin.network.ServerOrigin
import kotlinx.serialization.Serializable

@Serializable
data class AdminServer(
    val id: String,
    val baseUrl: String,
    val displayName: String,
    /** The provider this server was last signed in with; null after a password sign-in. Not a secret. */
    val signInProviderId: String? = null
) {
    fun validatedOrigin(): ServerOrigin = ServerOrigin.parse(baseUrl)

    val connectionProblem: OriginProblem?
        get() = try {
            validatedOrigin()
            null
        } catch (error: InvalidServerOrigin) {
            error.problem
        }
}
