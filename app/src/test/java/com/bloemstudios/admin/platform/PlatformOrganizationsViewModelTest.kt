package com.bloemstudios.admin.platform

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformOrganizationsViewModelTest {
    private val acme = PlatformFixtures.organizationJson(id = "o1", name = "Acme Media")
    private val north = PlatformFixtures.organizationJson(id = "o2", name = "North Sea", status = "suspended", slug = "north-sea")

    private fun harness(body: String) = PlatformHarness(route = { HttpStatusCode.OK to body })

    @Test
    fun `the first page lists every status and keeps the counts`() = runTest {
        val harness = harness(PlatformFixtures.organizationsPage(acme, north, next = "c2"))
        val viewModel = PlatformOrganizationsViewModel()

        viewModel.load(harness.session)

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals(listOf("Acme Media", "North Sea"), state.organizations.map { it.name })
        assertEquals(12L, state.organizations.first().membership_count)
        assertEquals(25L, state.organizations.first().profile_count)
        assertEquals(3L, state.organizations.first().library_count)
        assertEquals("c2", state.nextCursor)
        val request = harness.passRequests.single()
        assertEquals("/api/bloem/v2/admin/platform/organizations/", request.url.encodedPath)
        assertNull("no status filter is sent for any status", request.url.parameters["status"])
        assertNull(request.url.parameters["query"])
        assertEquals("50", request.url.parameters["limit"])
    }

    @Test
    fun `search and status go to the server and start again from the first page`() = runTest {
        val harness = harness(PlatformFixtures.organizationsPage(north))
        val viewModel = PlatformOrganizationsViewModel()

        viewModel.load(harness.session, query = "  north  ", status = "suspended")

        val request = harness.passRequests.single()
        assertEquals("north", request.url.parameters["query"])
        assertEquals("suspended", request.url.parameters["status"])
        assertNull(request.url.parameters["cursor"])
        assertEquals("north", viewModel.state.value.query)
        assertEquals("suspended", viewModel.state.value.status)
    }

    @Test
    fun `show more appends the next page with its cursor and stops when there is none`() = runTest {
        val harness = PlatformHarness(route = { request ->
            HttpStatusCode.OK to
                if (request.url.parameters["cursor"] == "c2") PlatformFixtures.organizationsPage(north)
                else PlatformFixtures.organizationsPage(acme, next = "c2")
        })
        val viewModel = PlatformOrganizationsViewModel()
        viewModel.load(harness.session)

        viewModel.loadMore(harness.session)
        viewModel.loadMore(harness.session)

        assertEquals(listOf("Acme Media", "North Sea"), viewModel.state.value.organizations.map { it.name })
        assertNull(viewModel.state.value.nextCursor)
        assertEquals("one request for each page, none when there was no cursor", 2, harness.passRequests.size)
        assertEquals("c2", harness.passRequests.last().url.parameters["cursor"])
    }

    @Test
    fun `a refusal of platform authority says so and a server error names the page`() = runTest {
        val refused = PlatformHarness(route = { HttpStatusCode.Forbidden to PlatformFixtures.error("insufficient_platform_authority", "Platform administrator authority required") })
        val viewModel = PlatformOrganizationsViewModel()
        viewModel.load(refused.session)
        assertEquals(ErrorText.Res(R.string.platform_orgs_no_access), viewModel.state.value.error)
        assertTrue(viewModel.state.value.organizations.isEmpty())
        assertFalse(viewModel.state.value.loading)

        val broken = PlatformHarness(route = { HttpStatusCode.ServiceUnavailable to """{"error":"tenant_unavailable","message":"tenant_unavailable"}""" })
        val other = PlatformOrganizationsViewModel()
        other.load(broken.session)
        assertEquals(ErrorText.Http(R.string.platform_orgs_load_error, 503), other.state.value.error)
    }

    @Test
    fun `a failed reload keeps the rows that were on screen`() = runTest {
        var failing = false
        val harness = PlatformHarness(route = {
            if (failing) HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("tenant_unavailable", "tenant_unavailable")
            else HttpStatusCode.OK to PlatformFixtures.organizationsPage(acme)
        })
        val viewModel = PlatformOrganizationsViewModel()
        viewModel.load(harness.session)
        failing = true

        viewModel.load(harness.session)

        assertEquals(listOf("Acme Media"), viewModel.state.value.organizations.map { it.name })
        assertEquals(ErrorText.Http(R.string.platform_orgs_load_error, 503), viewModel.state.value.error)
    }
}
