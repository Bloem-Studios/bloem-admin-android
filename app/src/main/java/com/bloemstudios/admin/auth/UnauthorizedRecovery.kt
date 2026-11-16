package com.bloemstudios.admin.auth

import com.bloemstudios.admin.servers.AdminServer

fun interface UnauthorizedRecovery {
    suspend fun recover(rejected: AccessTokenSnapshot): RecoveryResult
}

data class AccessTokenSnapshot(val token: String?, val generation: Long) {
    override fun toString(): String =
        "AccessTokenSnapshot(token=<redacted>, generation=$generation)"
}

sealed interface RecoveryResult {
    data class Retry(val snapshot: AccessTokenSnapshot) : RecoveryResult
    data object LoginRequired : RecoveryResult
    data class Unavailable(val cause: Throwable) : RecoveryResult {
        override fun toString(): String = "Unavailable(cause=<redacted>)"
    }
}

fun interface RefreshExchange {
    suspend fun exchange(server: AdminServer, refreshToken: String): RefreshResponse
}

enum class CredentialChangeReason { AUTHENTICATED, REFRESHED, CLEARED }

fun interface CredentialChangeObserver {
    fun onChanged(serverId: String, reason: CredentialChangeReason)
}
