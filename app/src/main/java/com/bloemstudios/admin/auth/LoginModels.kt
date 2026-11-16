package com.bloemstudios.admin.auth

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String, val provider: String? = null)

@Serializable
data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long? = null
)

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class RefreshResponse(val access_token: String, val refresh_token: String, val expires_in: Long? = null)

@Serializable
data class LoggedInUser(
    val id: Int,
    val username: String,
    val email: String,
    val role: String,
    val permissions: List<String>,
    val download_allowed: Boolean
)
