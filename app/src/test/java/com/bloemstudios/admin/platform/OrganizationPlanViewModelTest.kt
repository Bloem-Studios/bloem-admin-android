package com.bloemstudios.admin.platform

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationPlanViewModelTest {
    private val id = PlatformFixtures.ORG_ID
    private val basic = PlatformFixtures.template("basic", "Basic", 3)
    private val premium = PlatformFixtures.template("premium", "Premium", 2)

    private class Server(
        var entitlement: Pair<HttpStatusCode, String> = HttpStatusCode.OK to PlatformFixtures.entitlement(),
        var templates: Pair<HttpStatusCode, String>,
        var dryRun: Pair<HttpStatusCode, String> = HttpStatusCode.OK to PlatformFixtures.dryRun(),
        var apply: Pair<HttpStatusCode, String> = HttpStatusCode.OK to PlatformFixtures.applied(),
    ) {
        val bodies = mutableMapOf<String, String>()
        fun route(request: HttpRequestData): Pair<HttpStatusCode, String> {
            val path = request.url.encodedPath
            return when {
                request.method == HttpMethod.Get && path.endsWith("/entitlement") -> entitlement
                request.method == HttpMethod.Get && path.endsWith("/entitlement-templates") -> templates
                request.method == HttpMethod.Post && path.endsWith("/dry-run") -> dryRun.also { bodies["dry-run"] = request.bodyText() }
                request.method == HttpMethod.Post && path.endsWith("/apply") -> apply.also { bodies["apply"] = request.bodyText() }
                else -> HttpStatusCode.NotFound to PlatformFixtures.error("not_found")
            }
        }
    }

    private fun plans() = HttpStatusCode.OK to PlatformFixtures.templates(basic, premium)

    private fun harness(server: Server) = PlatformHarness(route = server::route)

    private fun viewModel(key: String = "key-1") = OrganizationPlanViewModel(newIdempotencyKey = { key })

    @Test
    fun `the plan loads with the organization's entitlement and the plans on offer`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel()

        viewModel.load(harness.session, id)

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertTrue(state.available)
        assertEquals("basic", state.entitlement?.template_key)
        assertEquals(3L, state.entitlement?.template_revision)
        assertEquals(25, state.entitlement?.tenant_limits?.slots)
        assertEquals(listOf("Basic", "Premium"), state.templates.map { it.name })
        assertEquals("only plans on offer are asked for", "enabled", harness.passCalls("/entitlement-templates").single().url.parameters["status"])
    }

    @Test
    fun `an organization with no plan yet has none and can still choose one`() = runTest {
        val server = Server(entitlement = HttpStatusCode.OK to PlatformFixtures.noEntitlement(), templates = plans())
        val viewModel = viewModel()

        viewModel.load(harness(server).session, id)

        assertFalse(viewModel.state.value.entitlement!!.hasPlan)
        assertEquals(2, viewModel.state.value.templates.size)
    }

    @Test
    fun `a server without plan routes hides the plan`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented)) {
            val server = Server(entitlement = status to PlatformFixtures.error("not_found"), templates = status to PlatformFixtures.error("not_found"))
            val viewModel = viewModel()

            viewModel.load(harness(server).session, id)

            assertFalse("for $status", viewModel.state.value.available)
            assertFalse(viewModel.state.value.loading)
        }
    }

    @Test
    fun `plans that cannot be read are said so while the current plan still shows`() = runTest {
        val server = Server(templates = HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("entitlements_unavailable", "entitlements_unavailable"))
        val viewModel = viewModel()

        viewModel.load(harness(server).session, id)

        assertEquals("basic", viewModel.state.value.entitlement?.template_key)
        assertTrue(viewModel.state.value.templatesError)
        assertTrue(viewModel.state.value.available)
    }

    @Test
    fun `an entitlement that cannot be read is said so`() = runTest {
        val server = Server(entitlement = HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("entitlements_unavailable", "entitlements_unavailable"), templates = plans())
        val viewModel = viewModel()

        viewModel.load(harness(server).session, id)

        assertNull(viewModel.state.value.entitlement)
        assertEquals(ErrorText.Http(R.string.platform_plan_load_error, 503), viewModel.state.value.error)
    }

    @Test
    fun `previewing sends the chosen plan and revision and keeps what the server would change`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)

        viewModel.select("premium")
        viewModel.preview(harness.session, id)

        assertEquals("""{"template_key":"premium","template_revision":2}""", server.bodies["dry-run"])
        val preview = viewModel.state.value.preview
        assertNotNull(preview)
        assertTrue(preview!!.dryRun.changed)
        assertEquals("Premium", preview.template.name)
        assertEquals(1, preview.dryRun.changes.size)
        assertFalse(viewModel.state.value.previewing)
        assertFalse("the token is never printed", preview.dryRun.toString().contains("dry-run-token-1"))
    }

    @Test
    fun `choosing another plan drops the preview of the first`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("premium")
        viewModel.preview(harness.session, id)

        viewModel.select("basic")

        assertNull(viewModel.state.value.preview)
        assertEquals("basic", viewModel.state.value.selectedKey)
    }

    @Test
    fun `nothing is previewed without a chosen plan`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)

        viewModel.preview(harness.session, id)

        assertTrue(harness.passCalls("/dry-run").isEmpty())
    }

    @Test
    fun `a preview that fails says so and offers nothing to apply`() = runTest {
        val server = Server(templates = plans(), dryRun = HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("entitlements_unavailable", "entitlements_unavailable"))
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("premium")

        viewModel.preview(harness.session, id)

        assertNull(viewModel.state.value.preview)
        assertEquals(ErrorText.Http(R.string.platform_plan_preview_error, 503), viewModel.state.value.error)
        assertFalse(viewModel.state.value.previewing)
    }

    @Test
    fun `applying sends the token of the preview with a key that makes a repeat harmless, then reloads the plan`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel(key = "idempotency-7")
        viewModel.load(harness.session, id)
        viewModel.select("premium")
        viewModel.preview(harness.session, id)
        server.entitlement = HttpStatusCode.OK to PlatformFixtures.entitlement(key = "premium", revision = 2)

        val applied = viewModel.apply(harness.session, id, "Acme Media")

        assertTrue(applied)
        assertEquals(
            """{"template_key":"premium","template_revision":2,"dry_run_token":"dry-run-token-1","idempotency_key":"idempotency-7"}""",
            server.bodies["apply"],
        )
        val state = viewModel.state.value
        assertEquals("premium", state.entitlement?.template_key)
        assertNull(state.preview)
        assertNull(state.selectedKey)
        assertEquals(PlatformNotice.PlanApplied("Acme Media", "Premium"), state.notice)
        assertFalse(state.applying)
    }

    @Test
    fun `a preview with no change cannot be applied`() = runTest {
        val server = Server(templates = plans(), dryRun = HttpStatusCode.OK to PlatformFixtures.dryRun(key = "basic", revision = 3, changed = false))
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("basic")
        viewModel.preview(harness.session, id)

        val applied = viewModel.apply(harness.session, id, "Acme Media")

        assertFalse(applied)
        assertTrue(harness.passCalls("/apply").isEmpty())
        assertFalse(viewModel.state.value.preview!!.dryRun.changed)
    }

    @Test
    fun `nothing is applied without a preview`() = runTest {
        val server = Server(templates = plans())
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("premium")

        assertFalse(viewModel.apply(harness.session, id, "Acme Media"))

        assertTrue(harness.passCalls("/apply").isEmpty())
    }

    @Test
    fun `a stale pass during apply is not replayed and asks for a new preview`() = runTest {
        val server = Server(templates = plans(), apply = HttpStatusCode.Unauthorized to PlatformFixtures.error("authorization_state_stale"))
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("premium")
        viewModel.preview(harness.session, id)

        val applied = viewModel.apply(harness.session, id, "Acme Media")

        assertFalse(applied)
        assertEquals("the apply was sent once", 1, harness.passCalls("/apply").size)
        assertNull("the preview is out of date", viewModel.state.value.preview)
        assertEquals(ErrorText.Res(R.string.platform_plan_changed), viewModel.state.value.error)
        assertFalse(viewModel.state.value.applying)
    }

    @Test
    fun `a refused apply keeps the preview so it can be tried again, and says what to do`() = runTest {
        val server = Server(
            templates = plans(),
            apply = HttpStatusCode.UnprocessableEntity to """{"error":"validation_failed","message":"confirmation_token is invalid, expired, or belongs to another apply target"}""",
        )
        val harness = harness(server)
        val viewModel = viewModel()
        viewModel.load(harness.session, id)
        viewModel.select("premium")
        viewModel.preview(harness.session, id)

        val applied = viewModel.apply(harness.session, id, "Acme Media")

        assertFalse(applied)
        assertEquals(ErrorText.Res(R.string.platform_plan_apply_error), viewModel.state.value.error)
        assertFalse(viewModel.state.value.applying)
    }
}
