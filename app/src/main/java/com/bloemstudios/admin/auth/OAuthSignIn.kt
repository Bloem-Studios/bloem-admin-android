package com.bloemstudios.admin.auth

import com.bloemstudios.admin.network.ServerOrigin
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun randomToken(random: SecureRandom): String = base64Url(ByteArray(32).also(random::nextBytes))

data class Pkce(val verifier: String, val challenge: String) {
    override fun toString(): String = "Pkce(challenge=$challenge)"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = randomToken(random)
            return Pkce(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

fun newAppState(random: SecureRandom = SecureRandom()): String = randomToken(random)

class PendingOAuth(
    val origin: ServerOrigin,
    val serverId: String,
    val providerId: String,
    val providerName: String,
    val pkce: Pkce,
    val appState: String,
    val startedAtMillis: Long
) {
    override fun toString(): String =
        "PendingOAuth(origin=${origin.value}, serverId=$serverId, providerId=$providerId, startedAtMillis=$startedAtMillis)"
}

/** The browser start URL: the saved origin plus [nativeStartPath], which must begin with exactly one `/`. */
fun nativeStartUrl(origin: ServerOrigin, nativeStartPath: String, pkce: Pkce, appState: String): String {
    require(nativeStartPath.startsWith("/") && !nativeStartPath.startsWith("//")) { "native start path must start with one /" }
    require(nativeStartPath.none { it == '\\' || it == '?' || it == '#' || it.isWhitespace() }) { "native start path is not a plain path" }
    return origin.resolve(nativeStartPath).toString() +
        "?code_challenge=${pkce.challenge}&code_challenge_method=S256&app_state=$appState"
}

sealed interface OAuthRedirect {
    data class Code(val code: String) : OAuthRedirect {
        override fun toString(): String = "Code(redacted)"
    }

    data class Failed(val reason: String) : OAuthRedirect

    data object Untrusted : OAuthRedirect
}

private const val REDIRECT_SCHEME = "org.siloserver.silo"
private const val REDIRECT_PATH = "/auth/callback"

fun parseOAuthRedirect(uri: String, pending: PendingOAuth): OAuthRedirect {
    val params = mutableMapOf<String, String>()
    try {
        val parsed = URI(uri)
        if (parsed.scheme != REDIRECT_SCHEME || parsed.rawPath != REDIRECT_PATH) return OAuthRedirect.Untrusted
        for (pair in parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (params.put(name, value) != null) return OAuthRedirect.Untrusted
        }
    } catch (_: Exception) {
        // Redirects come from other apps and browsers: anything malformed is not ours.
        return OAuthRedirect.Untrusted
    }
    if (params["iss"] != pending.origin.issuer) return OAuthRedirect.Untrusted
    val state = params["state"]
    // An empty value counts as absent: `code=` is not a code and `error=` is not an error.
    val error = params["error"]?.takeIf { it.isNotEmpty() }
    if (error != null) {
        return if (state == null || state == pending.appState) OAuthRedirect.Failed(error) else OAuthRedirect.Untrusted
    }
    val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return OAuthRedirect.Failed("login_failed")
    if (state != pending.appState || params["server"] != pending.serverId) return OAuthRedirect.Untrusted
    return OAuthRedirect.Code(code)
}
