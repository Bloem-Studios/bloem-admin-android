package com.bloemstudios.admin.network

import io.ktor.http.encodeURLPathPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginTest {
    @Test
    fun canonicalizesCaseWhitespaceRootSlashAndExplicitNonDefaultPort() {
        assertEquals(
            "https://example.test:8443",
            ServerOrigin.parse("  HTTPS://Example.Test:8443/  ").value
        )
    }

    @Test
    fun typedAddressWithoutASchemeAssumesHttps() {
        assertEquals("https://example.test", ServerOrigin.parseTyped("example.test").value)
        assertEquals("https://example.test:8443", ServerOrigin.parseTyped("  Example.Test:8443/  ").value)
        assertEquals("https://example.test", ServerOrigin.parseTyped("HTTPS://example.test").value)
    }

    @Test
    fun typedAddressKeepsEveryOtherRule() {
        val cases = mapOf(
            "http://example.test" to OriginProblem.HTTPS_REQUIRED,
            "ftp://example.test" to OriginProblem.HTTPS_REQUIRED,
            "example.test/admin" to OriginProblem.ORIGIN_ONLY,
            "user:pass@example.test" to OriginProblem.ORIGIN_ONLY,
            "example.test?q=1" to OriginProblem.ORIGIN_ONLY,
            "example.test#fragment" to OriginProblem.ORIGIN_ONLY
        )
        cases.forEach { (raw, expected) ->
            val error = assertThrows(InvalidServerOrigin::class.java) { ServerOrigin.parseTyped(raw) }
            assertEquals(raw, expected, error.problem)
        }
        assertThrows(InvalidServerOrigin::class.java) { ServerOrigin.parseTyped("   ") }
    }

    @Test
    fun removesDefaultHttpsPort() {
        assertEquals("https://example.test", ServerOrigin.parse("https://example.test:443/").value)
    }

    @Test
    fun rejectsCleartextAndNonOriginComponents() {
        val cases = mapOf(
            "http://example.test" to OriginProblem.HTTPS_REQUIRED,
            "https:///" to OriginProblem.HOST_REQUIRED,
            "https://user:pass@example.test" to OriginProblem.ORIGIN_ONLY,
            "https://@example.test" to OriginProblem.ORIGIN_ONLY,
            "https://:@example.test" to OriginProblem.ORIGIN_ONLY,
            "https://example.test/admin" to OriginProblem.ORIGIN_ONLY,
            "https://example.test?q=1" to OriginProblem.ORIGIN_ONLY,
            "https://example.test?" to OriginProblem.ORIGIN_ONLY,
            "https://example.test/#" to OriginProblem.ORIGIN_ONLY,
            "https://example.test/#fragment" to OriginProblem.ORIGIN_ONLY
        )
        cases.forEach { (raw, expected) ->
            val error = assertThrows(InvalidServerOrigin::class.java) { ServerOrigin.parse(raw) }
            assertEquals(expected, error.problem)
        }
    }

    @Test
    fun resolvesApiPathsWithoutDoubleSlashesOrAuthorityChanges() {
        val origin = ServerOrigin.parse("https://example.test/")
        assertEquals("https://example.test/api/v1/health", origin.resolve("/api/v1/health").toString())
    }

    private val origin = ServerOrigin.parse("https://example.test")

    /** A bare dot segment is sent as %2E so no client or server resolves it away. */
    private fun expectedSegment(encoded: String) = if (encoded == "..") "%2E%2E" else encoded

    @Test
    fun encodedIdsStayOneSegment() {
        listOf("a/b", "a?b", "a#b", "a%b", "..", "x/../y", "a b", "100%", "a%2Fb").forEach { id ->
            val encoded = id.encodeURLPathPart()
            val url = origin.resolve("/api/v2/admin/users/$encoded/devices")
            assertEquals("segments for '$id'", listOf("api", "v2", "admin", "users", expectedSegment(encoded), "devices"), url.encodedPath.trim('/').split('/'))
            assertEquals("/api/v2/admin/users/${expectedSegment(encoded)}/devices", url.encodedPath)
            assertEquals("query for '$id'", "", url.encodedQuery)
            assertEquals("fragment for '$id'", "", url.encodedFragment)
        }
    }

    @Test
    fun queryIsAttachedAsGivenAndNotReencoded() {
        val url = origin.resolve("/api/v2/admin/logs/app?limit=100&q=a%26b%2Bc%25%3F&level=info")
        assertEquals("/api/v2/admin/logs/app", url.encodedPath)
        assertEquals("limit=100&q=a%26b%2Bc%25%3F&level=info", url.encodedQuery)
        assertEquals("a&b+c%?", url.parameters["q"])
    }

    @Test
    fun encodedIdWithQueryKeepsBoth() {
        val url = origin.resolve("/api/v2/admin/tasks/${"a?b".encodeURLPathPart()}/history?limit=20")
        assertEquals("/api/v2/admin/tasks/a%3Fb/history", url.encodedPath)
        assertEquals("limit=20", url.encodedQuery)
    }

    @Test
    fun issuerIsTheServersOriginForm() {
        assertEquals("https://example.test", ServerOrigin.parse("https://Example.TEST:443/").issuer)
        assertEquals("https://example.test:8443", ServerOrigin.parse("https://example.test:8443").issuer)
        assertEquals("https://[2001:db8::1]", ServerOrigin.parse("https://[2001:db8::1]/").issuer)
    }
}
