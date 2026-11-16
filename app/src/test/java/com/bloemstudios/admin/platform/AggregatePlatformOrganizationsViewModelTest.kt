package com.bloemstudios.admin.platform

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatePlatformOrganizationsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val a = AdminServer("a", "https://a.example.test", "Loft")
    private val b = AdminServer("b", "https://b.example.test", "Shed")
    private val c = AdminServer("c", "https://c.example.test", "Barn")

    private val acme = PlatformFixtures.organizationJson(id = "o1", name = "Acme Media")
    private val north = PlatformFixtures.organizationJson(id = "o2", name = "North Sea", status = "suspended", slug = "north-sea")

    /** One fake server: its capability document, its answer to a platform pass, and its list of organizations. */
    private class FakeServer(
        val capabilities: Pair<HttpStatusCode, String>,
        val mint: HttpStatusCode = HttpStatusCode.OK,
        val organizations: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to PlatformFixtures.organizationsPage() },
    ) {
        val requests = mutableListOf<HttpRequestData>()
    }

    private fun supported(vararg rows: String, next: String? = null) = FakeServer(
        capabilities = HttpStatusCode.OK to """{"features":{"organization_memberships":true},"feature_tokens":[]}""",
        organizations = { HttpStatusCode.OK to PlatformFixtures.organizationsPage(*rows, next = next) },
    )

    private val servers = mutableMapOf<String, FakeServer>()

    private fun engineFor(fake: FakeServer) = MockEngine { request ->
            fake.requests += request
            val path = request.url.encodedPath
            when {
                path == "/api/bloem/v2/capabilities" -> respond(fake.capabilities.second, fake.capabilities.first, json)
                request.method == HttpMethod.Post && path.endsWith("/admin/session") ->
                    if (fake.mint == HttpStatusCode.OK) respond(PlatformFixtures.platformSession(token = "pass"), HttpStatusCode.OK, json)
                    else respond(PlatformFixtures.error("forbidden", "forbidden"), fake.mint, json)
                path.endsWith("/platform/organizations/") -> fake.organizations(request).let { respond(it.second, it.first, json) }
                else -> respond(PlatformFixtures.error("not_found"), HttpStatusCode.NotFound, json)
            }
    }

    private fun clientFor(server: AdminServer) = ApiClient(server.baseUrl, "token-${server.id}", engineFor(servers.getValue(server.id)))

    /** Sessions whose pass client talks to the same fake server as the account client. */
    private fun viewModel() = AggregatePlatformOrganizationsViewModel(sessionFactory = { account ->
        val fake = servers.entries.first { (id, _) -> account.origin.value.contains("//$id.") }.value
        PlatformSession(account, clientFactory = { origin, snapshot, recovery -> ApiClient(origin, snapshot, engineFor(fake), recovery) })
    })

    @Test
    fun `each server keeps its own organizations in its own section`() = runTest {
        servers["a"] = supported(acme, north)
        servers["b"] = supported(PlatformFixtures.organizationJson(id = "o9", name = "Shed Co"))
        val viewModel = viewModel()

        viewModel.load(listOf(a, b), ::clientFor)

        val sections = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf("Acme Media", "North Sea"), sections.getValue("a").result.getOrThrow().organizations.map { it.name })
        assertEquals(listOf("Shed Co"), sections.getValue("b").result.getOrThrow().organizations.map { it.name })
        assertEquals(listOf("a", "b"), viewModel.sections.value.map { it.server.id })
    }

    @Test
    fun `a server without organizations is said so and no platform pass is asked of it`() = runTest {
        servers["a"] = FakeServer(capabilities = HttpStatusCode.NotFound to PlatformFixtures.error("not_found"))
        val viewModel = viewModel()

        viewModel.load(listOf(a), ::clientFor)

        val section = viewModel.sections.value.single().result.getOrThrow()
        assertFalse(section.supported)
        assertTrue(section.organizations.isEmpty())
        assertTrue("no pass was minted", servers.getValue("a").requests.none { it.url.encodedPath.endsWith("/admin/session") })
    }

    @Test
    fun `a server that refuses platform access fails its own section only`() = runTest {
        servers["a"] = supported(acme)
        servers["c"] = FakeServer(
            capabilities = HttpStatusCode.OK to """{"features":{"organization_memberships":true}}""", mint = HttpStatusCode.Forbidden,
        )
        val viewModel = viewModel()

        viewModel.load(listOf(a, c), ::clientFor)

        val sections = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(sections.getValue("a").result.isSuccess)
        val failure = sections.getValue("c").result.exceptionOrNull()
        assertTrue(failure is ApiError && failure.statusCode == 403)
    }

    @Test
    fun `show more adds the next page of one server only`() = runTest {
        var asked = 0
        servers["a"] = FakeServer(
            capabilities = HttpStatusCode.OK to """{"features":{"organization_memberships":true}}""",
            organizations = { request ->
                asked++
                HttpStatusCode.OK to
                    if (request.url.parameters["cursor"] == "c2") PlatformFixtures.organizationsPage(north)
                    else PlatformFixtures.organizationsPage(acme, next = "c2")
            },
        )
        servers["b"] = supported(PlatformFixtures.organizationJson(id = "o9", name = "Shed Co"))
        val viewModel = viewModel()
        viewModel.load(listOf(a, b), ::clientFor)
        val before = servers.getValue("b").requests.size

        viewModel.loadMore(a, ::clientFor)

        val sections = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf("Acme Media", "North Sea"), sections.getValue("a").result.getOrThrow().organizations.map { it.name })
        assertNull(sections.getValue("a").result.getOrThrow().nextCursor)
        assertEquals("the other server was not asked", before, servers.getValue("b").requests.size)
        assertEquals(2, asked)
    }

    @Test
    fun `a search narrows the rows of every server by name or short name`() {
        val rows = listOf(
            com.bloemstudios.admin.organization.OrganizationSummary(id = "1", name = "Acme Media", status = "active", slug = "acme"),
            com.bloemstudios.admin.organization.OrganizationSummary(id = "2", name = "North Sea", status = "suspended", slug = "nsea"),
        )
        assertEquals(listOf("1", "2"), filterOrganizations(rows, "  ").map { it.id })
        assertEquals(listOf("1"), filterOrganizations(rows, "ACME").map { it.id })
        assertEquals(listOf("2"), filterOrganizations(rows, "nsea").map { it.id })
        assertEquals(emptyList<String>(), filterOrganizations(rows, "zzz").map { it.id })
    }
}
