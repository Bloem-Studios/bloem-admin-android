package com.bloemstudios.admin.tenants

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class TenantsViewModelTest {
    @Test
    fun `load decodes a full tenant list`() = runTest {
        val engine = MockEngine {
            respond(
                content = """[{"tenant_id":"t1","name":"Acme Hosting","external_ref":{"operator_id":"op-1","service_id":"svc-1"},"limits":{"slots":10,"transcodes":2},"usage":{"slots_used":7},"frozen":false,"frozen_reason":"","applied_entitlement_revision":3}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        val state = viewModel.probeState.value
        assertTrue(state is TenantsProbeState.Loaded)
        val tenant = (state as TenantsProbeState.Loaded).tenants.first()
        assertEquals("t1", tenant.tenant_id)
        assertEquals("Acme Hosting", tenant.name)
        assertEquals(10, tenant.limits?.slots)
        assertEquals(2, tenant.limits?.transcodes)
        assertEquals(7, tenant.usage?.slots_used)
        assertFalse(tenant.frozen)
        assertEquals(3L, tenant.applied_entitlement_revision)
    }

    @Test
    fun `load decodes a tenant with optional fields absent`() = runTest {
        val engine = MockEngine {
            respond(
                content = """[{"tenant_id":"t2","name":"Frozen Co","limits":{"slots":5},"usage":{"slots_used":5},"frozen":true}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        val tenant = (viewModel.probeState.value as TenantsProbeState.Loaded).tenants.first()
        assertTrue(tenant.frozen)
        assertNull(tenant.frozen_reason)
        assertNull(tenant.applied_entitlement_revision)
    }

    // The trickiest new logic: a 404 from GET /tenants means "this server
    // doesn't have the tenants feature at all" -> NotSupported, which the
    // caller (AdminGateScreen) uses to hide the Tenants tab entirely. Any
    // OTHER failure (5xx, network error, decode failure) is a genuine
    // transient problem and must NOT be treated the same way -- it should
    // land on Error so the caller still shows the tab with a normal
    // error/retry state.
    @Test
    fun `load treats a 404 as NotSupported (feature does not exist), hiding the entry point`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"no such route"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        assertTrue(viewModel.probeState.value is TenantsProbeState.NotSupported)
    }

    @Test
    fun `load treats a 500 as a genuine error, NOT as unsupported`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"internal","message":"database is on fire"}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        val state = viewModel.probeState.value
        assertTrue(state is TenantsProbeState.Error)
        assertFalse(state is TenantsProbeState.NotSupported)
    }

    @Test
    fun `load treats a 403 as a genuine error, NOT as unsupported`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"forbidden","message":"Admin access required"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        assertTrue(viewModel.probeState.value is TenantsProbeState.Error)
    }

    @Test
    fun `load treats a network failure as a genuine error, NOT as unsupported`() = runTest {
        val engine = MockEngine { throw java.io.IOException("connection reset") }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.load(client)

        assertTrue(viewModel.probeState.value is TenantsProbeState.Error)
    }

    @Test
    fun `loadMembers decodes the member list`() = runTest {
        val engine = MockEngine {
            respond(
                content = """[{"user_id":42,"username":"alice","email":"alice@example.test","status":"active"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.loadMembers("t1", client)

        val state = viewModel.membersState.value
        assertTrue(state is TenantMembersState.Loaded)
        val member = (state as TenantMembersState.Loaded).members.first()
        assertEquals(42, member.user_id)
        assertEquals("alice", member.username)
        assertEquals("alice@example.test", member.email)
        assertEquals("active", member.status)
    }

    @Test
    fun `loadMembers surfaces an error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"tenant not found"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.loadMembers("gone", client)

        assertTrue(viewModel.membersState.value is TenantMembersState.Error)
    }

    @Test
    fun `clearMembers resets membersState to null`() = runTest {
        val engine = MockEngine {
            respond(content = "[]", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = TenantsViewModel()

        viewModel.loadMembers("t1", client)
        assertNotNull(viewModel.membersState.value)

        viewModel.clearMembers()
        assertNull(viewModel.membersState.value)
    }
    @Test fun `canceling tenant retry restores prior state instead of leaving Checking`() = runTest {
        val entered = CompletableDeferred<Unit>(); var hold = false
        val api = ApiClient("https://fixture.invalid", null, MockEngine {
            if (hold) { entered.complete(Unit); awaitCancellation() }
            respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = TenantsViewModel(); vm.load(api); val previous = vm.probeState.value
            hold = true; val read = launch { vm.load(api) }; entered.await()
            assertTrue(vm.probeState.value is TenantsProbeState.Checking)
            read.cancelAndJoin(); assertEquals(previous, vm.probeState.value)
        } finally { api.close() }
    }
    @Test fun `canceling member retry restores prior state instead of leaving Loading`() = runTest {
        val entered = CompletableDeferred<Unit>(); var hold = false
        val api = ApiClient("https://fixture.invalid", null, MockEngine {
            if (hold) { entered.complete(Unit); awaitCancellation() }
            respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = TenantsViewModel(); vm.loadMembers("a", api); val previous = vm.membersState.value
            hold = true; val read = launch { vm.loadMembers("a", api) }; entered.await()
            assertTrue(vm.membersState.value is TenantMembersState.Loading)
            read.cancelAndJoin(); assertEquals(previous, vm.membersState.value)
        } finally { api.close() }
    }

}
