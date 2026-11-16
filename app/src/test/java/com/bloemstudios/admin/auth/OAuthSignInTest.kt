package com.bloemstudios.admin.auth

import com.bloemstudios.admin.network.ServerOrigin
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthSignInTest {
    private val origin = ServerOrigin.parse("https://example.test")
    private val pkce = Pkce.generate()
    private val pending = PendingOAuth(origin, "srv-1", "26", "Corp SSO", pkce, "state-abc", 1_000L)
    private val unreserved = Regex("^[A-Za-z0-9\\-._~]{43}$")

    private fun redirect(vararg params: Pair<String, String>, base: String = "org.siloserver.silo:/auth/callback"): String =
        base + "?" + params.joinToString("&") { "${it.first}=${it.second}" }

    private val good = arrayOf("code" to "c0de", "state" to "state-abc", "server" to "srv-1", "iss" to "https://example.test")

    @Test
    fun rfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun generatedPkce() {
        val a = Pkce.generate(SecureRandom())
        val b = Pkce.generate()
        assertTrue(unreserved.matches(a.verifier))
        assertEquals(Pkce.challengeFor(a.verifier), a.challenge)
        assertNotEquals(a.verifier, b.verifier)
        assertFalse(a.toString().contains(a.verifier))
        assertFalse(pending.toString().contains(pending.pkce.verifier))
    }

    @Test
    fun appState() {
        assertTrue(unreserved.matches(newAppState()))
        assertNotEquals(newAppState(), newAppState())
    }

    @Test
    fun nativeStartUrlIsExact() {
        assertEquals(
            "https://example.test/api/v2/auth/oauth/26/native/start?code_challenge=C&code_challenge_method=S256&app_state=S",
            nativeStartUrl(origin, "/api/v2/auth/oauth/26/native/start", Pkce("v", "C"), "S")
        )
    }

    @Test
    fun nativeStartUrlRefusesOtherOrigins() {
        for (p in listOf("https://evil.test/x", "//evil.test/x", "api/v2/x", "")) {
            assertThrows(IllegalArgumentException::class.java) { nativeStartUrl(origin, p, pkce, "S") }
        }
    }

    @Test
    fun redirectAccepted() {
        assertEquals(OAuthRedirect.Code("c0de"), parseOAuthRedirect(redirect(*good), pending))
        assertFalse(OAuthRedirect.Code("c0de").toString().contains("c0de"))
    }

    private fun with(key: String, value: String?): Array<Pair<String, String>> =
        good.filter { it.first != key }.plus(listOfNotNull(value?.let { key to it })).toTypedArray()

    @Test
    fun redirectUntrusted() {
        val cases = listOf(
            redirect(*with("iss", "https://other.test")),
            redirect(*with("iss", "https://example.test:8443")),
            redirect(*with("iss", null)),
            redirect(*with("state", "nope")),
            redirect(*with("state", null)),
            redirect(*with("server", "srv-2")),
            redirect(*with("server", null)),
            redirect(*good, base = "https:/auth/callback"),
            redirect(*good, base = "org.siloserver.silo:/other"),
            redirect("error" to "account_disabled", "iss" to "https://other.test"),
            redirect("error" to "account_disabled", "iss" to "https://example.test", "state" to "nope")
        )
        cases.forEach { assertEquals(it, OAuthRedirect.Untrusted, parseOAuthRedirect(it, pending)) }
    }

    @Test
    fun redirectFailures() {
        assertEquals(
            OAuthRedirect.Failed("account_disabled"),
            parseOAuthRedirect(redirect("error" to "account_disabled", "iss" to "https://example.test"), pending)
        )
        assertEquals(
            OAuthRedirect.Failed("state_invalid"),
            parseOAuthRedirect(redirect("error" to "state_invalid", "iss" to "https://example.test", "state" to "state-abc"), pending)
        )
        assertEquals(
            OAuthRedirect.Failed("login_failed"),
            parseOAuthRedirect(redirect("iss" to "https://example.test"), pending)
        )
    }

    @Test
    fun emptyCodeAndErrorValuesCountAsAbsent() {
        val iss = "iss" to "https://example.test"
        // No usable code and no usable error: a failed sign-in, never a redeemable empty code.
        assertEquals(
            OAuthRedirect.Failed("login_failed"),
            parseOAuthRedirect(redirect("code" to "", "state" to "state-abc", "server" to "srv-1", iss), pending)
        )
        assertEquals(
            OAuthRedirect.Failed("login_failed"),
            parseOAuthRedirect(redirect("error" to "", iss), pending)
        )
        // An empty error beside a real code does not hide the code.
        assertEquals(
            OAuthRedirect.Code("c0de"),
            parseOAuthRedirect(redirect("error" to "", *good), pending)
        )
        // An empty code beside a real error still reports the error.
        assertEquals(
            OAuthRedirect.Failed("account_disabled"),
            parseOAuthRedirect(redirect("code" to "", "error" to "account_disabled", iss), pending)
        )
    }

    @Test
    fun nativeOAuthFiltersAndOrders() {
        val json = """{"password_login":true,"items":[
            {"id":"local","display_name":"Silo","mode":"credentials","default":true},
            {"id":"a","display_name":"A","mode":"oauth","default":false,"installation_id":"1","native_start_path":"/api/v2/auth/oauth/1/native/start"},
            {"id":"n","display_name":"N","mode":"network","default":false,"native_start_path":"/x","network_sign_in_path":"/y"},
            {"id":"nopath","display_name":"P","mode":"oauth","default":false,"installation_id":"2"},
            {"id":"b","display_name":"B","mode":"oauth","default":true,"installation_id":"3","native_start_path":"/api/v2/auth/oauth/3/native/start","icon_url":"i"}
        ]}"""
        val providers = Json { ignoreUnknownKeys = true }.decodeFromString<AuthProviders>(json)
        assertEquals(listOf("b", "a"), providers.nativeOAuth.map { it.id })
        assertEquals("3", providers.nativeOAuth.first().installation_id)
    }

    /** Authentik leads sign-in whatever order the server lists it in and whichever provider it marks default. */
    @Test
    fun authentikIsListedFirstWhateverTheServerSays() {
        fun oauth(id: String, name: String, default: Boolean) =
            AuthProvider(id, name, "oauth", default, id, "/api/v2/auth/oauth/$id/native/start")
        val providers = AuthProviders(true, listOf(
            oauth("g", "Google", true), oauth("x", "Corp SSO", false), oauth("ak", "Authentik", false),
        ))
        assertEquals(listOf("ak", "g", "x"), providers.nativeOAuth.map { it.id })
        // The id counts as well as the name, and case does not matter.
        val byId = AuthProviders(true, listOf(oauth("g", "Google", true), oauth("authentik-prod", "Company login", false)))
        assertEquals(listOf("authentik-prod", "g"), byId.nativeOAuth.map { it.id })
        // Re-login still puts the remembered provider before Authentik.
        assertEquals(listOf("g", "authentik-prod"), byId.nativeOAuthFor("g").map { it.id })
        // Without one, the default leads and the rest keep the server's order.
        val none = AuthProviders(true, listOf(oauth("a", "A", false), oauth("b", "B", true), oauth("c", "C", false)))
        assertEquals(listOf("b", "a", "c"), none.nativeOAuth.map { it.id })
    }

    @Test
    fun rememberedProviderIsListedFirstWhenStillOffered() {
        fun oauth(id: String, default: Boolean) =
            AuthProvider(id, id, "oauth", default, id, "/api/v2/auth/oauth/$id/native/start")
        val providers = AuthProviders(true, listOf(oauth("a", false), oauth("b", true), oauth("c", false)))
        assertEquals(listOf("b", "a", "c"), providers.nativeOAuthFor(null).map { it.id })
        assertEquals(listOf("c", "b", "a"), providers.nativeOAuthFor("c").map { it.id })
        assertEquals(listOf("a", "b", "c"), providers.nativeOAuthFor("a").map { it.id })
        // No longer offered (or never native): fall back to the server's order.
        assertEquals(listOf("b", "a", "c"), providers.nativeOAuthFor("gone").map { it.id })
        assertEquals(listOf("b", "a", "c"), providers.nativeOAuthFor("local").map { it.id })
    }

    private val cb = "org.siloserver.silo:/auth/callback?"

    @Test
    fun percentEncodedValuesAreDecoded() {
        val uri = cb + "code=c%30de&state=state-abc&server=srv-1&iss=https%3A%2F%2Fexample.test"
        assertEquals(OAuthRedirect.Code("c0de"), parseOAuthRedirect(uri, pending))
    }

    @Test
    fun duplicateParametersAreUntrusted() {
        val base = "code=c0de&state=state-abc&server=srv-1&iss=https%3A%2F%2Fexample.test"
        for (extra in listOf("code=other", "state=state-abc", "c%6Fde=other")) {
            assertEquals(extra, OAuthRedirect.Untrusted, parseOAuthRedirect("$cb$base&$extra", pending))
        }
    }

    @Test
    fun malformedEscapesAreUntrustedNotACrash() {
        for (uri in listOf(cb + "code=%zz", cb + "code=c0de&state=%&server=srv-1&iss=https%3A%2F%2Fexample.test", cb + "%zz=1")) {
            assertEquals(uri, OAuthRedirect.Untrusted, parseOAuthRedirect(uri, pending))
        }
    }
}
