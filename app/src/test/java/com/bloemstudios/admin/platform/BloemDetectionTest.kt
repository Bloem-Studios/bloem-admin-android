package com.bloemstudios.admin.platform

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.listOrganizationMemberships
import com.bloemstudios.admin.network.api.listPlatformOrganizations
import com.bloemstudios.admin.organization.OrganizationFixtures
import com.bloemstudios.admin.organization.canOpenSupportAccess
import com.bloemstudios.admin.organization.listAdminContexts
import com.bloemstudios.admin.ui.shell.aggregateDestinations
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app works with every Silo server. The Bloem extras are offered only where `GET /api/bloem/v2/capabilities` answers,
 * the answer is found once per server, and a plain Silo server is never sent another `/api/bloem/` request.
 */
class BloemDetectionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val text = headersOf(HttpHeaders.ContentType, "text/plain")
    private val html = headersOf(HttpHeaders.ContentType, "text/html")

    private val bloemCapabilities =
        """{"api":"bloem/v2","features":{"organization_memberships":true},"feature_tokens":["organization_support_v1"]}"""

    /** Every path the server was sent, in order. */
    private val seen = mutableListOf<String>()

    /** A server that answers like plain upstream Silo: its shared routes work, everything under `/api/bloem/` is `404 page not found`. */
    private fun plainSilo() = ApiClient("https://silo.test", "token", MockEngine { request ->
        val path = request.url.encodedPath
        seen += path
        when {
            path == "/api/v2/admin/server/status" -> respond(V2Fixtures.SERVER_STATUS, HttpStatusCode.OK, json)
            path.startsWith("/api/bloem/") -> respond("404 page not found", HttpStatusCode.NotFound, text)
            else -> error("unexpected $path")
        }
    })

    /** A Bloem server with organizations, whose account is the server Owner. */
    private fun bloemServer() = ApiClient("https://bloem.test", "token", MockEngine { request ->
        val path = request.url.encodedPath
        seen += path
        when (path) {
            "/api/bloem/v2/capabilities" -> respond(bloemCapabilities, HttpStatusCode.OK, json)
            "/api/bloem/v2/organizations" -> respond(OrganizationFixtures.memberships(OrganizationFixtures.membership()), HttpStatusCode.OK, json)
            "/api/v2/account/me" -> respond(
                """{"id":"1","username":"alex","email":"j@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""",
                HttpStatusCode.OK, json,
            )
            "/api/v2/admin/users/1" -> respond(V2Fixtures.adminUser().replace("\"is_owner\":false", "\"is_owner\":true"), HttpStatusCode.OK, json)
            "/api/v2/admin/server/status" -> respond(V2Fixtures.SERVER_STATUS, HttpStatusCode.OK, json)
            else -> error("unexpected $path")
        }
    })

    private fun bloemPaths() = seen.filter { it.startsWith("/api/bloem/") }

    // ---- A plain Silo server ----------------------------------------------------------------------------

    @Test
    fun `a plain Silo server answers 404 and is not a Bloem server`() = runTest {
        val client = plainSilo()
        assertFalse(client.isBloemServer())
        assertNull(client.bloem.capabilities())
        assertTrue(client.bloem.knownPlainSilo)
    }

    @Test
    fun `a plain Silo server is asked once and then no Bloem route is sent to it`() = runTest {
        val client = plainSilo()

        assertFalse(client.supportsPlatformOrganizations())
        assertFalse(client.canOpenSupportAccess())
        assertTrue(client.listAdminContexts(platformAdmin = true).none { it.organizationId != null })
        assertTrue(client.listAdminContexts(platformAdmin = false).isEmpty())
        repeat(3) { assertFalse(client.isBloemServer()) }

        assertEquals("one capability check, nothing else under /api/bloem/", listOf("/api/bloem/v2/capabilities"), bloemPaths())
    }

    @Test
    fun `a Bloem route called by mistake on a plain Silo server is refused without being sent`() = runTest {
        val client = plainSilo()
        client.isBloemServer()
        seen.clear()

        for (call in listOf<suspend () -> Unit>(
            { client.listOrganizationMemberships() },
            { client.listPlatformOrganizations(null, null) },
        )) {
            try {
                call()
                fail("the call must not succeed")
            } catch (error: ApiError) {
                assertEquals(404, error.statusCode)
            }
        }
        assertTrue("nothing went to the server", seen.isEmpty())
    }

    @Test
    fun `a page that is not a capability document is a server without the route`() = runTest {
        val client = ApiClient("https://proxy.test", "token", MockEngine { request ->
            seen += request.url.encodedPath
            respond("<html>Welcome</html>", HttpStatusCode.OK, html)
        })
        assertFalse(client.isBloemServer())
        assertFalse(client.isBloemServer())
        assertEquals(listOf("/api/bloem/v2/capabilities"), seen)
    }

    // ---- A Bloem server ---------------------------------------------------------------------------------

    @Test
    fun `a Bloem server is found once and keeps its extras`() = runTest {
        val client = bloemServer()

        assertTrue(client.isBloemServer())
        assertTrue(client.supportsPlatformOrganizations())
        assertTrue(client.canOpenSupportAccess())
        val contexts = client.listAdminContexts(platformAdmin = true)
        assertEquals(listOf("platform", "organization:${OrganizationFixtures.ORG_ID}"), contexts.map { it.key })

        assertEquals("capabilities is read once and kept", 1, seen.count { it == "/api/bloem/v2/capabilities" })
        assertEquals(1, seen.count { it == "/api/bloem/v2/organizations" })
    }

    @Test
    fun `a Bloem server without organizations is Bloem but is not offered them`() = runTest {
        val client = ApiClient("https://bloem.test", "token", MockEngine { request ->
            seen += request.url.encodedPath
            respond("""{"api":"bloem/v2","features":{"organization_memberships":false},"feature_tokens":[]}""", HttpStatusCode.OK, json)
        })
        assertTrue(client.isBloemServer())
        assertFalse(client.supportsPlatformOrganizations())
    }

    // ---- An answer that says nothing --------------------------------------------------------------------

    @Test
    fun `an answer that says nothing is not remembered and the extras stay out of sight until the server answers`() = runTest {
        var up = false
        val client = ApiClient("https://flaky.test", "token", MockEngine { request ->
            seen += request.url.encodedPath
            if (up) respond(bloemCapabilities, HttpStatusCode.OK, json) else respond("bad gateway", HttpStatusCode.BadGateway, text)
        })

        assertFalse(client.isBloemServer())
        assertFalse("not decided", client.bloem.knownPlainSilo)
        assertFalse(client.supportsPlatformOrganizations())

        up = true
        assertTrue(client.isBloemServer())
        assertEquals(3, seen.size)
        assertTrue(client.isBloemServer())
        assertEquals("the good answer is kept", 3, seen.size)
    }

    // ---- What the menus show ----------------------------------------------------------------------------

    @Test
    fun `the menus carry no Bloem page unless the server is a Bloem server`() = runTest {
        for ((client, isBloem) in listOf(plainSilo() to false, bloemServer() to true)) {
            val extras = client.isBloemServer()
            assertEquals(isBloem, extras)
            val single = singleServerDestinations(
                tenantsVisible = false,
                supportAccessVisible = client.canOpenSupportAccess(),
                organizationsVisible = client.supportsPlatformOrganizations(),
            ).map { it.id }
            val all = aggregateDestinations(organizationsVisible = client.supportsPlatformOrganizations()).map { it.id }
            assertEquals(isBloem, "platform.organizations" in single)
            assertEquals(isBloem, "platform.support-access" in single)
            assertEquals(isBloem, "platform.organizations" in all)
            assertTrue("the shared pages stay", "live.streams" in single && "live.streams" in all)
        }
    }
}
