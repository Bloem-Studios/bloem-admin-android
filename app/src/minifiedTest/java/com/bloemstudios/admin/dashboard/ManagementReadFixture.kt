package com.bloemstudios.admin.dashboard

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.activity.*
import com.bloemstudios.admin.audit.*
import com.bloemstudios.admin.invitations.*
import com.bloemstudios.admin.jobs.*
import com.bloemstudios.admin.logs.*
import com.bloemstudios.admin.requests.*
import com.bloemstudios.admin.serverconfig.*
import com.bloemstudios.admin.tenants.*
import com.bloemstudios.admin.users.*
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual management screens and GETs, all inside the minified target ABI. */
class ManagementReadFixture(val kind: String) {
    val reads = AtomicInteger()
    val held = AtomicInteger()
    val canceled = AtomicInteger()
    val writes = AtomicInteger()
    val saved = AtomicInteger()
    private val release = CompletableDeferred<Unit>()
    private var error: () -> Boolean = { true }
    private var rowCount: () -> Int = { 0 }
    val cursors = java.util.concurrent.CopyOnWriteArrayList<String>()
    val validators = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val primary = when (kind) {
        "activity" -> "/api/v2/admin/playback-history"
        "audit", "page" -> "/api/v2/admin/logs/audit"
        "logs" -> "/api/v2/admin/logs/app"
        "jobs" -> "/api/v2/admin/tasks"
        "requests" -> "/api/v2/admin/requests"
        "settings" -> "/api/v2/admin/settings/effective"
        "invitations" -> "/api/v2/admin/invitations"
        "review", "save" -> "/api/v2/admin/settings/effective"
        "sessions" -> "/api/v2/admin/users/1/login-sessions"
        "members" -> "/api/bloem/v2/admin/platform/tenants/a/members"
        else -> "/api/v2/admin/users"
    }
    private companion object {
        const val EMPTY_PAGE = """{"items":[],"page":{"has_more":false}}"""
    }
    private var problems = 0
    private fun problem(status: Int, slug: String, title: String) =
        """{"type":"https://siloserver.org/docs/api/v2/problems/$slug","title":"$title","status":$status,"detail":"Fixture $title.","instance":"urn:silo:request:fixture-${++problems}"}"""
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val api = ApiClient("https://fixture.invalid", null, MockEngine { request ->
        val path = request.url.encodedPath
        if (request.method != HttpMethod.Get) {
            writes.incrementAndGet(); validators += request.headers[HttpHeaders.IfMatch].orEmpty()
            if (kind == "save") {
                held.incrementAndGet()
                try { release.await() } catch (e: CancellationException) { canceled.incrementAndGet(); throw e }
                return@MockEngine respond("""{"key":"example","value":"my draft","restart_required":false}""", headers=json)
            }
            return@MockEngine respond(problem(412, "precondition_failed", "Precondition Failed"), HttpStatusCode.PreconditionFailed, json)
        }
        if (path != primary) return@MockEngine respond(when {
            path.endsWith("/server/status") -> """{"started_at":"2026-08-28T10:00:00Z","restart_required":false,"restart_mark_count":0,"restart_requested":false,"health":{"postgres":{"configured":true},"redis":{"configured":true},"errors_24h":0,"warnings_24h":0},"artwork_storage":{"locked":false,"status_known":true,"private_locked":false}}"""
            path.endsWith("/tenants") -> """[{"tenant_id":"a","name":"Fixture tenant","external_ref":{},"limits":{},"usage":{},"frozen":false}]"""
            else -> EMPTY_PAGE
        }, headers=json)
        val round = reads.incrementAndGet()
        cursors += request.url.parameters["cursor"].orEmpty()
        val heldRound = if (kind == "members") 1 else 2
        if (round == heldRound) {
            held.incrementAndGet()
            try { release.await() } catch (e: CancellationException) { canceled.incrementAndGet(); throw e }
        }
        if (kind !in setOf("page", "review", "save", "members") && ((round == 1 && kind !in setOf("settings", "invitations")) || round == 3)) {
            return@MockEngine respond(problem(503, "service_unavailable", "Service Unavailable"), HttpStatusCode.ServiceUnavailable, json)
        }
        val body = when (kind) {
            "activity", "audit", "logs", "jobs", "requests" -> EMPTY_PAGE
            "settings" -> (1..if (round == 1) 20 else 1).joinToString(prefix="{", postfix="}") { "\"setting$it\":\"value\"" }
            "invitations" -> (1..if (round == 1) 20 else 1).joinToString(prefix="""{"items":[""", postfix="]}") {
                """{"id":"$it","email":"fixture$it@example.test","delivery":"email","role":"user","library_ids":[],"create_profile":true,"show_tour":false,"note":"","invited_by":"1","invited_by_name":"Fixture admin","status":"pending","expires_at":"2026-11-30T00:00:00Z","created_at":"2026-11-01T00:00:00Z"}"""
            }
            "review", "save" -> """{"example":"${if(round == 1) "original" else "latest"}"}"""
            "page" -> if (request.url.parameters["cursor"] == null)
                """{"items":[{"id":"1","timestamp":"2026-11-01T00:00:00Z","client_ip":"192.0.2.1","method":"GET","path":"first-page","status_code":200,"duration_ms":3}],"page":{"has_more":true,"next_cursor":"cursor-2"}}"""
                else """{"items":[{"id":"2","timestamp":"2026-11-01T00:00:01Z","client_ip":"192.0.2.1","method":"GET","path":"second-page","status_code":200,"duration_ms":4}],"page":{"has_more":false}}"""
            "members" -> "[]"
            else -> EMPTY_PAGE
        }
        respond(body, headers = if(kind in setOf("review", "save")) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(if(round == 1) "\"original\"" else "\"latest\"")) else json)
    })

    @Composable fun Render() {
        when (kind) {
            "activity" -> { val vm = viewModel<ActivityViewModel>(); SideEffect { error = { vm.errorMessage.value != null } }; ActivityScreen(api) }
            "audit", "page" -> { val vm = viewModel<AuditViewModel>(); SideEffect { error = { vm.errorMessage.value != null }; rowCount = {vm.entries.value.size} }; AuditScreen(api) }
            "logs" -> { val vm = viewModel<LogsViewModel>(); SideEffect { error = {vm.errorMessage.value != null} }; LogsScreen(api) }
            "jobs" -> { val vm = viewModel<JobsViewModel>(); SideEffect { error = {vm.errorMessage.value != null} }; JobsScreen(api) }
            "requests" -> { val vm = viewModel<RequestsViewModel>(); SideEffect { error = {vm.loadError.value != null} }; RequestsScreen(api) }
            "settings" -> { val vm = viewModel<SettingsPagesViewModel>(key = "settings:mgmt"); SideEffect { error = {vm.state.value.loadError != null}; rowCount = {vm.state.value.snapshot?.values?.size ?: 0} }; ServerSettingsHost(api, scopeKey = "mgmt") }
            "invitations" -> { val vm = viewModel<InvitationsViewModel>(); SideEffect { error = {vm.errorMessage.value != null}; rowCount = {vm.invitations.value.size} }; InvitationsScreen(api) {} }
            "review", "save" -> SettingEditorDialog(api, "example", {}, { saved.incrementAndGet() })
            "sessions" -> UserSessionsPanel(AdminUser("1","Fixture user","fixture@example.test","user",true),api)
            "members" -> TenantsScreen(api)
            else -> { val vm = viewModel<UsersViewModel>(); SideEffect { error = {vm.loadError.value != null} }; UsersScreen(api) }
        }
    }
    fun hasError() = error()
    fun rows() = rowCount()
    fun releaseHeldRead() { release.complete(Unit) }
    fun close() { releaseHeldRead(); api.close() }
}
