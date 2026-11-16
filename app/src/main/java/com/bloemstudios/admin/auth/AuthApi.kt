package com.bloemstudios.admin.auth

import com.bloemstudios.admin.network.ApiClient

/**
 * Shared login POST/decode logic. Used by both the add-server flow and the
 * gate's re-login flow (triggered by a 401 from the admin-access probe) so
 * the request/response handling lives in exactly one place.
 */
suspend fun login(client: ApiClient, username: String, password: String): LoginResponse =
    client.post("/api/v2/auth/login", LoginRequest(username, password))

/**
 * POSTs to /api/v2/auth/refresh with [refreshToken]. On success the server
 * returns a NEW access token AND a new refresh token (the old refresh token
 * is single-use); on failure it's a 401 with either session_revoked or
 * invalid_token, both of which just mean the caller needs a fresh login --
 * ApiClient surfaces both the same way (as an ApiError with statusCode 401),
 * so there's nothing here that needs to distinguish them.
 */
suspend fun refresh(client: ApiClient, refreshToken: String): RefreshResponse =
    client.post("/api/v2/auth/refresh", RefreshRequest(refreshToken))

/** GET /api/v2/system/identity: the stable server id, read before an OAuth start. */
suspend fun getServerIdentity(client: ApiClient): ServerIdentity = client.get("/api/v2/system/identity")

/** GET /api/v2/auth/providers: the sign-in methods the server offers. */
suspend fun listAuthProviders(client: ApiClient): AuthProviders = client.get("/api/v2/auth/providers")

/** POST /api/v2/auth/oauth/complete: redeems the one-time [code] with the PKCE [verifier]. */
suspend fun completeOAuthLogin(client: ApiClient, code: String, verifier: String): OAuthCompletion =
    client.post("/api/v2/auth/oauth/complete", OAuthCompleteRequest(code, verifier))
