package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.auth.completeOAuthLogin
import com.bloemstudios.admin.auth.getServerIdentity
import com.bloemstudios.admin.auth.listAuthProviders
import com.bloemstudios.admin.auth.login
import com.bloemstudios.admin.auth.refresh
import com.bloemstudios.admin.invitations.CreateInvitationRequest
import com.bloemstudios.admin.jobs.TaskTrigger
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.AccessGroupDraft
import com.bloemstudios.admin.organization.AdminSessionRequest
import com.bloemstudios.admin.organization.BulkKind
import com.bloemstudios.admin.organization.CreateOrganizationInvitation
import com.bloemstudios.admin.organization.OrganizationFixtures
import com.bloemstudios.admin.organization.PeopleFilter
import com.bloemstudios.admin.platform.PlatformFixtures
import com.bloemstudios.admin.push.PushRegisterRequest
import com.bloemstudios.admin.streams.CommandIdentity
import com.bloemstudios.admin.users.CreateUserRequest
import com.bloemstudios.admin.users.detail.SettingIdentity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls every public function of `network/api` (and the auth login/refresh calls) once and checks
 * each request against `api-contract.json`, which `scripts/extract-api-contract.py` generates from
 * the server's OpenAPI documents: the operation exists, every query parameter is declared for it,
 * every required query parameter is sent, every numeric query value is within the parameter's
 * declared minimum and maximum, and a required body is present as non-empty JSON.
 * The server rejects undeclared query parameters and missing bodies, so a request that fails here
 * fails on a real server.
 */
class ApiContractTest {
    private val contract: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("api-contract.json")) {
            "api-contract.json is missing; run scripts/extract-api-contract.py"
        }.bufferedReader().use { it.readText() }
    ).jsonObject

    private class Case(
        val function: String,
        val operation: String,
        val response: String = """{"items":[]}""",
        val call: suspend ApiClient.() -> Unit
    )

    private val user = V2Fixtures.adminUser()
    private val delivery = V2Fixtures.invitationDelivery()
    private val schedule = """{"task_key":"k","triggers":[]}"""
    private val identity = CommandIdentity("c1", 1)

    private val cases: List<Case> = listOf(
        Case("listPlaybackHistory", "GET /api/v2/admin/playback-history") { listPlaybackHistory("u1", "c1", 50) },

        Case("listInvitations", "GET /api/v2/admin/invitations") { listInvitations() },
        Case("createInvitation", "POST /api/v2/admin/invitations", delivery) { createInvitation(CreateInvitationRequest("a@example.test", "user", "n")) },
        Case("resendInvitation", "POST /api/v2/admin/invitations/{id}/resend", delivery) { resendInvitation("i1") },
        Case("revokeInvitation", "DELETE /api/v2/admin/invitations/{id}", "") { revokeInvitation("i1") },

        Case("listRequests", "GET /api/v2/admin/requests") { listRequests() },
        Case("listRequestQueuePage", "GET /api/v2/admin/requests") {
            listRequestQueuePage(com.bloemstudios.admin.requests.RequestQueueView.Failed, "dune", "series", "7", "c1", 500)
        },
        Case("getRequestCounts", "GET /api/v2/admin/requests/counts", """{"needs_approval":1,"in_progress":2,"failed":3,"done":4}""") { getRequestCounts() },
        Case("getApiKeyCapabilities", "GET /api/v2/admin/api-keys/capabilities", """{"available":true}""") { getApiKeyCapabilities() },
        Case("listApiKeys", "GET /api/v2/admin/api-keys") { listApiKeys("c1", 50) },
        Case("getApiKeyWithValidator", "GET /api/v2/admin/api-keys/{id}", """{"id":"1","user_id":"1","label":"k","key_prefix":"bk_","rate_tier":"standard","scopes":[],"created_at":"2026-11-01T10:00:00Z"}""") { getApiKeyWithValidator("1") },
        Case("revokeApiKey", "DELETE /api/v2/admin/api-keys/{id}", "") { revokeApiKey("1", "\"v1\"") },
        Case("getRequestCapabilities", "GET /api/v2/admin/requests/capabilities", """{"available":true}""") { getRequestCapabilities() },
        Case("listRequestEvents", "GET /api/v2/admin/requests/{id}/events") { listRequestEvents("r1") },
        Case("actOnRequest", "POST /api/v2/admin/requests/{id}/approve", "") { actOnRequest("r1", RequestAction.APPROVE, "ok") },
        Case("actOnRequest", "POST /api/v2/admin/requests/{id}/decline", "") { actOnRequest("r1", RequestAction.DECLINE) },
        Case("actOnRequest", "POST /api/v2/admin/requests/{id}/retry", "") { actOnRequest("r1", RequestAction.RETRY) },
        Case("actOnRequest", "POST /api/v2/admin/requests/{id}/cancel", "") { actOnRequest("r1", RequestAction.CANCEL) },

        Case("listSessions", "GET /api/v2/admin/sessions") { listSessions() },
        Case("sendSessionCommand", "POST /api/v2/admin/sessions/{session_id}/pause", V2Fixtures.PLAYBACK_COMMAND_RECEIPT) { sendSessionCommand("s1", SessionCommand.PAUSE, identity) },
        Case("sendSessionCommand", "POST /api/v2/admin/sessions/{session_id}/resume", V2Fixtures.PLAYBACK_COMMAND_RECEIPT) { sendSessionCommand("s1", SessionCommand.RESUME, identity) },
        Case("sendSessionCommand", "POST /api/v2/admin/sessions/{session_id}/stop", V2Fixtures.PLAYBACK_COMMAND_RECEIPT) { sendSessionCommand("s1", SessionCommand.STOP, identity) },
        Case("terminateSession", "POST /api/v2/admin/sessions/{session_id}/terminate", V2Fixtures.PLAYBACK_TERMINATE_RECEIPT) { terminateSession("s1", "r") },
        Case("messageSession", "POST /api/v2/admin/sessions/{session_id}/message", V2Fixtures.PLAYBACK_COMMAND_RECEIPT) { messageSession("s1", "t", "m", identity) },

        Case("listAuditLogs", "GET /api/v2/admin/logs/audit") { listAuditLogs("u1", "c1", 100) },
        Case("listAppLogs", "GET /api/v2/admin/logs/app") { listAppLogs("info", "scan", "c1", 100) },

        Case("listTasks", "GET /api/v2/admin/tasks") { listTasks() },
        Case("getTask", "GET /api/v2/admin/tasks/{key}", V2Fixtures.adminTask("k")) { getTask("k") },
        Case("runTask", "POST /api/v2/admin/tasks/{key}/run", V2Fixtures.adminTask("k")) { runTask("k") },
        Case("cancelTask", "POST /api/v2/admin/tasks/{key}/cancel", V2Fixtures.adminTask("k")) { cancelTask("k") },
        Case("getTaskSchedule", "GET /api/v2/admin/tasks/{key}/triggers", schedule) { getTaskSchedule("k") },
        Case("updateTaskSchedule", "PUT /api/v2/admin/tasks/{key}/triggers", schedule) { updateTaskSchedule("k", listOf(TaskTrigger("startup")), "\"v1\"") },
        Case("listJobHistory", "GET /api/v2/admin/jobs") { listJobHistory("c1", 20) },
        Case("listTaskHistory", "GET /api/v2/admin/tasks/{key}/history") { listTaskHistory("k", "c1", 20) },

        Case("getNodeCapabilities", "GET /api/v2/admin/nodes/capabilities", """{"state":"available","allowed":true,"drain":true,"summary":true}""") { getNodeCapabilities() },
        Case("listNodeSummaries", "GET /api/v2/admin/nodes/summary") { listNodeSummaries() },
        Case("drainNode", "POST /api/v2/admin/nodes/{id}/drain", """{"node_id":"7","drain_requested":true,"drain_state":"none","active_jobs":0}""") { drainNode("7") },
        Case("resumeNode", "POST /api/v2/admin/nodes/{id}/resume", """{"node_id":"7","drain_requested":false,"drain_state":"none","active_jobs":0}""") { resumeNode("7") },
        Case("listNodeConfigurations", "GET /api/v2/admin/nodes") { listNodeConfigurations() },
        Case("setNodeEnabled", "PUT /api/v2/admin/nodes/{id}", """{"id":"7","enabled":false}""") { setNodeEnabled("7", false, "\"rev-1\"") },

        Case("getBackupCapabilities", "GET /api/v2/admin/backups/capabilities", """{"state":"available","allowed":true}""") { getBackupCapabilities() },
        Case("listBackups", "GET /api/v2/admin/backups", """{"items":[],"warnings":[]}""") { listBackups() },
        Case("startBackup", "POST /api/v2/admin/backups", """{"id":"20261114T100000Z-manual","kind":"manual","status":"in_progress"}""") { startBackup() },
        Case("deleteBackup", "DELETE /api/v2/admin/backups/{id}", "") { deleteBackup("20261112T040000Z-nightly") },

        Case("listPluginInstallations", "GET /api/v2/admin/plugins/installations") { listPluginInstallations() },
        Case("probePluginInstallations", "GET /api/v2/admin/plugins/installations") { probePluginInstallations() },
        Case("restartPlugin", "POST /api/v2/admin/plugins/installations/{id}/restart", """{"id":"7","plugin_id":"p"}""") { restartPlugin("7") },
        Case("setPluginEnabled", "PUT /api/v2/admin/plugins/installations/{id}", """{"id":"7","plugin_id":"p"}""") { setPluginEnabled("7", true) },
        Case("updatePlugin", "POST /api/v2/admin/plugins/installations/{id}/update", """{"id":"7","plugin_id":"p"}""") { updatePlugin("7") },

        Case("getHealth", "EXEMPT GET /api/v1/health", "{}") { getHealth() },
        Case("getServerStatus", "GET /api/v2/admin/server/status", V2Fixtures.SERVER_STATUS) { getServerStatus() },
        Case("getAdminStats", "GET /api/v2/admin/stats", V2Fixtures.ADMIN_STATS) { getAdminStats(true) },
        Case("getAdminStats", "GET /api/v2/admin/stats", V2Fixtures.ADMIN_STATS) { getAdminStats(false) },
        Case("getSystemResources", "GET /api/v2/admin/system/resources", V2Fixtures.SYSTEM_RESOURCES) { getSystemResources() },
        Case("getBuildInfo", "GET /api/v2/admin/system/build", """{"available":true,"build_number":7,"dirty":false,"display":"v1.2","revision":"abc"}""") { getBuildInfo() },
        Case("listNodes", "GET /api/v2/admin/nodes") { listNodes() },
        Case("getPlaybackActivity", "GET /api/v2/admin/stats/playback-activity", """{"profiles_active_24h":2}""") { getPlaybackActivity(24, false) },
        Case("getPlaybackActivity", "GET /api/v2/admin/stats/playback-activity", """{"profiles_active_24h":2}""") { getPlaybackActivity(744, true) },
        Case("getTimeseries", "GET /api/v2/admin/stats/timeseries", """{"points":[]}""") { getTimeseries(1, false) },
        Case("getTimeseries", "GET /api/v2/admin/stats/timeseries", """{"points":[]}""") { getTimeseries(24, true) },
        // The windows the dashboard charts offer: hour, day, week, month.
        Case("getTimeseries", "GET /api/v2/admin/stats/timeseries", """{"points":[]}""") { getTimeseries(168, false) },
        Case("getTimeseries", "GET /api/v2/admin/stats/timeseries", """{"points":[]}""") { getTimeseries(720, true) },
        Case("getPlaybackActivity", "GET /api/v2/admin/stats/playback-activity", """{"profiles_active_24h":2}""") { getPlaybackActivity(1, false) },
        Case("getPlaybackActivity", "GET /api/v2/admin/stats/playback-activity", """{"profiles_active_24h":2}""") { getPlaybackActivity(168, false) },
        Case("getPlaybackActivity", "GET /api/v2/admin/stats/playback-activity", """{"profiles_active_24h":2}""") { getPlaybackActivity(720, true) },
        Case("getTopActivity", "GET /api/v2/admin/stats/top-activity", """{"titles":[],"profiles":[]}""") { getTopActivity(7, 5, false) },
        Case("getTopActivity", "GET /api/v2/admin/stats/top-activity", """{"titles":[],"profiles":[]}""") { getTopActivity(30, 25, true) },
        Case("listLibraries", "GET /api/v2/libraries") { listLibraries() },
        // The Libraries page: libraries, scan controls, scan runs, mount check, metadata refresh.
        Case("listAdminLibraries", "GET /api/v2/libraries") { listAdminLibraries() },
        Case("getScanCapability", "GET /api/v2/scan/capabilities", """{"state":"available"}""") { getScanCapability() },
        Case("startLibraryScan", "POST /api/v2/scan", """{"status":"accepted","mode":"library","library_id":"1"}""") { startLibraryScan("1") },
        Case("cancelLibraryScans", "POST /api/v2/scan/cancel", """{"cancelled":1,"library_id":"1"}""") { cancelLibraryScans("1") },
        Case("checkLibraryMount", "POST /api/v2/libraries/{id}/check-mount", """{"healthy":true,"roots":[]}""") { checkLibraryMount("1") },
        Case("refreshLibraryMetadata", "POST /api/v2/libraries/{id}/refresh-metadata", """{"id":"j1","state":"queued"}""") {
            refreshLibraryMetadata("1", com.bloemstudios.admin.libraries.RefreshScope.All)
        },
        Case("listScanRuns", "GET /api/v2/admin/autoscan/scans") { listScanRuns("running", 30) },
        Case("listLibraryRefreshJobs", "GET /api/v2/admin/jobs") { listLibraryRefreshJobs() },
        Case("cancelAdminJob", "POST /api/v2/admin/jobs/{id}/cancel", """{"id":"j1","state":"canceling"}""") { cancelAdminJob("j1") },
        Case("listUsersForDashboard", "GET /api/v2/admin/users") { listUsersForDashboard() },
        Case("getSettings", "GET /api/v2/admin/settings", V2Fixtures.SETTINGS) { getSettings() },
        Case("getEffectiveSettingsWithValidator", "GET /api/v2/admin/settings/effective", """{"k":"v"}""") { getEffectiveSettingsWithValidator() },
        Case("updateSetting", "PUT /api/v2/admin/settings/{key}", """{"key":"k","value":"v"}""") { updateSetting("k", "v", "\"v1\"") },
        Case("getRestartKeys", "GET /api/v2/admin/settings/restart-keys", """{"keys":["playback.hw_accel"],"prefixes":["s3."]}""") { getRestartKeys() },
        Case("getSensitiveStatus", "GET /api/v2/admin/settings/sensitive-status", """{"configured":[],"managed_by_env":[]}""") { getSensitiveStatus() },
        Case("updateSettings", "PUT /api/v2/admin/settings", """{"values":{"server.log_level":"debug"},"restart_required":false}""") {
            updateSettings(mapOf("server.log_level" to "debug"), "\"v1\"")
        },
        Case("checkSettingsConnection", "POST /api/v2/admin/settings/check/{kind}", """{"success":true,"message":""}""") { checkSettingsConnection("redis", emptyMap(), emptyList()) },
        Case("sendTestEmail", "POST /api/v2/admin/email/test", """{"ok":true,"duration_ms":10}""") { sendTestEmail("me@example.test") },
        Case("listServerChannels", "GET /api/v2/admin/notifications/server-channels", """{"items":[],"page":{"has_more":false}}""") { listServerChannels() },
        Case("testServerChannel", "POST /api/v2/admin/notifications/server-channels/{id}/test", """{"ok":true,"http_status":204,"duration_ms":10}""") { testServerChannel("c1") },
        Case("getExternalSignInCapabilities", "GET /api/v2/auth/external-sign-in/capabilities", """{"available":true,"connection_test":true}""") { getExternalSignInCapabilities() },
        Case("listPluginInstallations", "GET /api/v2/admin/plugins/installations", """{"items":[],"page":{"has_more":false}}""") { listPluginInstallations() },
        Case("testSignInProvider", "POST /api/v2/admin/plugins/installations/{id}/auth-binding/test", """{"ok":true,"steps":[],"callback_url":""}""") { testSignInProvider("7", "oidc") },
        Case("getNetworkAccessCapabilities", "GET /api/v2/network-access/capabilities", """{"available":true,"providers":[]}""") { getNetworkAccessCapabilities() },
        Case("getNetworkAccessStatus", "GET /api/v2/admin/network-access/{provider}/status", """{"provider":"tailscale","hosts":[]}""") { getNetworkAccessStatus("tailscale") },
        Case("connectNetworkAccess", "POST /api/v2/admin/network-access/{provider}/connect", """{"provider":"tailscale","hosts":[]}""") { connectNetworkAccess("tailscale", "api") },
        Case("disconnectNetworkAccess", "POST /api/v2/admin/network-access/{provider}/disconnect", """{"provider":"tailscale","hosts":[]}""") { disconnectNetworkAccess("tailscale", "api") },
        Case("getRequestCapabilities", "GET /api/v2/admin/requests/capabilities", """{"available":true,"guarded_configuration":true}""") { getRequestCapabilities() },
        Case("getRequestSettingsWithValidator", "GET /api/v2/admin/request-settings", """{"requests_enabled":true,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false}""") { getRequestSettingsWithValidator() },
        Case("updateRequestSettings", "PUT /api/v2/admin/request-settings", """{"requests_enabled":true,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false}""") {
            updateRequestSettings(com.bloemstudios.admin.serverconfig.RequestSettings(true, 5, 7, false, false), "\"r1\"")
        },
        Case("restartServer", "POST /api/v2/admin/server/restart", V2Fixtures.SERVER_RESTART) { restartServer() },

        Case("listTenants", "GET /api/bloem/v2/admin/platform/tenants", "[]") { listTenants() },
        Case("listTenantMembers", "GET /api/bloem/v2/admin/platform/tenants/{tenant_id}/members", "[]") { listTenantMembers("t1") },

        // Organization mode: account-token routes, then the context-pass routes.
        Case("listOrganizationMemberships", "GET /api/bloem/v2/organizations", OrganizationFixtures.memberships()) { listOrganizationMemberships() },
        Case("mintAdminSession", "POST /api/bloem/v2/admin/session", OrganizationFixtures.session()) { mintAdminSession(AdminSessionRequest("organization", OrganizationFixtures.ORG_ID)) },
        Case("getOrganizationOverview", "GET /api/bloem/v2/admin/organization/overview", OrganizationFixtures.overview()) { getOrganizationOverview() },
        Case("listOrganizationPeople", "GET /api/bloem/v2/admin/organization/people/", OrganizationFixtures.peoplePage()) {
            listOrganizationPeople(PeopleFilter("a", setOf("active", "suspended", "invited"), 3, "name"), "c1", 200)
        },
        Case("setOrganizationMembershipStatus", "PATCH /api/bloem/v2/admin/organization/people/{account_id}/memberships/current", OrganizationFixtures.personResponse()) {
            setOrganizationMembershipStatus(5, "suspended", 4)
        },
        Case("setOrganizationProfileGroup", "PATCH /api/bloem/v2/admin/organization/people/{account_id}/profiles/{profile_id}", OrganizationFixtures.personResponse()) {
            setOrganizationProfileGroup(5, "p1", 8, 4)
        },
        Case("listOrganizationGroups", "GET /api/bloem/v2/admin/organization/groups/", OrganizationFixtures.groups()) { listOrganizationGroups() },
        Case("listOrganizationLibraries", "GET /api/bloem/v2/admin/organization/libraries", OrganizationFixtures.libraries()) { listOrganizationLibraries() },
        Case("setOrganizationEntitlementStatus", "PUT /api/bloem/v2/admin/organization/entitlements/{folder_id}", """{"entitlement":${OrganizationFixtures.entitlement()}}""") {
            setOrganizationEntitlementStatus(2, "active", 9)
        },
        Case("listOrganizationInvitations", "GET /api/bloem/v2/admin/organization/invitations", OrganizationFixtures.invitations()) { listOrganizationInvitations() },
        Case("createOrganizationInvitation", "POST /api/bloem/v2/admin/organization/invitations", OrganizationFixtures.handoff()) {
            createOrganizationInvitation(CreateOrganizationInvitation("a@example.test", 7, 3, "n"))
        },
        Case("resendOrganizationInvitation", "POST /api/bloem/v2/admin/organization/invitations/{id}/resend", OrganizationFixtures.handoff()) { resendOrganizationInvitation("i1", 7) },
        Case("revokeOrganizationInvitation", "DELETE /api/bloem/v2/admin/organization/invitations/{id}", "") { revokeOrganizationInvitation("i1", 7) },
        Case("listOrganizationActivity", "GET /api/bloem/v2/admin/organization/activity", OrganizationFixtures.audit()) { listOrganizationActivity("c1") },
        Case("listOrganizationCohorts", "GET /api/bloem/v2/admin/organization/entitlement-cohorts", OrganizationFixtures.cohorts()) { listOrganizationCohorts(true) },
        Case("listOrganizationPolicyDecisions", "GET /api/bloem/v2/admin/organization/policy-decisions", OrganizationFixtures.decisions()) { listOrganizationPolicyDecisions("c1", 200) },
        Case("listOrganizationPeople", "GET /api/bloem/v2/admin/organization/people/", OrganizationFixtures.peoplePage()) {
            listOrganizationPeople(PeopleFilter("a", setOf("active"), 3, "recent_activity", "2026-11-06T09:00:00Z"), "c1", 1)
        },
        Case("getOrganizationPerson", "GET /api/bloem/v2/admin/organization/people/{account_id}/", OrganizationFixtures.personResponse()) { getOrganizationPerson(5) },
        Case("createOrganizationSelection", "POST /api/bloem/v2/admin/organization/people/selections", OrganizationFixtures.selection()) { createOrganizationSelection(PeopleFilter("a", setOf("active"), 3)) },
        Case("startOrganizationBulkJob", "POST /api/bloem/v2/admin/organization/people/bulk-jobs", OrganizationFixtures.bulkJob()) { startOrganizationBulkJob("t", BulkKind.ASSIGN_GROUP, 3) },
        Case("getOrganizationBulkJob", "GET /api/bloem/v2/admin/organization/people/bulk-jobs/{job_id}", OrganizationFixtures.bulkJob()) { getOrganizationBulkJob("job-1") },
        Case("createOrganizationGroup", "POST /api/bloem/v2/admin/organization/groups/", OrganizationFixtures.groupMutation()) { createOrganizationGroup("Guests", 7) },
        Case("updateOrganizationGroup", "PUT /api/bloem/v2/admin/organization/groups/{id}", OrganizationFixtures.groupMutation()) {
            updateOrganizationGroup(3, AccessGroupDraft.of(com.bloemstudios.admin.organization.AccessGroup(id = "3", name = "Family")).toBody(7))
        },
        Case("deleteOrganizationGroup", "DELETE /api/bloem/v2/admin/organization/groups/{id}", OrganizationFixtures.deletionImpact()) { deleteOrganizationGroup(3, 7) },
        Case("deleteOrganizationEntitlement", "DELETE /api/bloem/v2/admin/organization/entitlements/{folder_id}", "") { deleteOrganizationEntitlement(2, 9) },
        Case("getOrganizationPolicyDecision", "GET /api/bloem/v2/admin/organization/policy-decisions/{id}", OrganizationFixtures.decisionDetail()) { getOrganizationPolicyDecision(1) },

        // Organization support: reset links, sign-outs, profile edits, streams, capabilities and the Owner's support access.
        Case("getSupportCapabilities", "GET /api/bloem/v2/admin/organization/support-capabilities", OrganizationFixtures.supportCapabilities()) { getSupportCapabilities() },
        Case("createSupportPasswordReset", "POST /api/bloem/v2/admin/organization/people/{account_id}/password-reset", OrganizationFixtures.reset("link", "not_requested", OrganizationFixtures.RESET_URL)) {
            createSupportPasswordReset(5, "link", 4)
        },
        Case("listSupportSessions", "GET /api/bloem/v2/admin/organization/people/{account_id}/sessions", OrganizationFixtures.supportSessions()) { listSupportSessions(5) },
        Case("signOutSupportEverywhere", "DELETE /api/bloem/v2/admin/organization/people/{account_id}/sessions", OrganizationFixtures.signedOut()) { signOutSupportEverywhere(5, 4) },
        Case("signOutSupportSession", "DELETE /api/bloem/v2/admin/organization/people/{account_id}/sessions/{session_id}", "") { signOutSupportSession(5, "s1") },
        Case("listSupportDevices", "GET /api/bloem/v2/admin/organization/people/{account_id}/devices", OrganizationFixtures.supportDevices()) { listSupportDevices(5) },
        Case("signOutSupportDevice", "DELETE /api/bloem/v2/admin/organization/people/{account_id}/devices/{device_id}", "") { signOutSupportDevice(5, "d1") },
        Case("addSupportProfile", "POST /api/bloem/v2/admin/organization/people/{account_id}/profiles", OrganizationFixtures.profileResponse()) { addSupportProfile(5, "Kids", 3) },
        Case("renameSupportProfile", "PATCH /api/bloem/v2/admin/organization/people/{account_id}/profiles/{profile_id}/details", OrganizationFixtures.profileResponse()) {
            renameSupportProfile(5, "p1", "Bedroom", 3)
        },
        Case("clearSupportProfilePin", "DELETE /api/bloem/v2/admin/organization/people/{account_id}/profiles/{profile_id}/pin", OrganizationFixtures.profileOnly()) {
            clearSupportProfilePin(5, "p1", 3)
        },
        Case("removeSupportProfile", "DELETE /api/bloem/v2/admin/organization/people/{account_id}/profiles/{profile_id}", "") { removeSupportProfile(5, "p1", 3) },
        Case("listSupportStreams", "GET /api/bloem/v2/admin/organization/streams", OrganizationFixtures.supportStreams()) { listSupportStreams("c1", 100) },
        Case("stopSupportStream", "POST /api/bloem/v2/admin/organization/streams/{session_id}/stop", OrganizationFixtures.stopped()) { stopSupportStream("st1", "r") },
        Case("messageSupportStream", "POST /api/bloem/v2/admin/organization/streams/{session_id}/message", OrganizationFixtures.messageReceipt()) {
            messageSupportStream("st1", "t", "m", "c1", 7)
        },
        Case("endSupportAccess", "DELETE /api/bloem/v2/admin/organization/support-access", "") { endSupportAccess() },
        Case("getBloemFeatureTokens", "GET /api/bloem/v2/capabilities", """{"feature_tokens":["organization_support_v1"]}""") { getBloemFeatureTokens() },
        Case("listPlatformOrganizations", "GET /api/bloem/v2/admin/platform/organizations/", OrganizationFixtures.platformOrganizations()) {
            listPlatformOrganizations("acme", "c1", 200, "suspended")
        },
        Case("getPlatformOrganization", "GET /api/bloem/v2/admin/platform/organizations/{id}/", PlatformFixtures.organization()) { getPlatformOrganization(PlatformFixtures.ORG_ID) },
        Case("listPlatformOrganizationMemberships", "GET /api/bloem/v2/admin/platform/organizations/{id}/memberships", PlatformFixtures.membershipsPage()) {
            listPlatformOrganizationMemberships(PlatformFixtures.ORG_ID, "c1", 200)
        },
        Case("suspendPlatformOrganization", "POST /api/bloem/v2/admin/platform/organizations/{id}/suspend", PlatformFixtures.lifecycleAnswer("suspended")) {
            suspendPlatformOrganization(PlatformFixtures.ORG_ID, 7)
        },
        Case("reactivatePlatformOrganization", "POST /api/bloem/v2/admin/platform/organizations/{id}/reactivate", PlatformFixtures.lifecycleAnswer("active")) {
            reactivatePlatformOrganization(PlatformFixtures.ORG_ID, 8)
        },
        Case("getPlatformOrganizationEntitlement", "GET /api/bloem/v2/admin/platform/organizations/{id}/entitlement", PlatformFixtures.entitlement()) {
            getPlatformOrganizationEntitlement(PlatformFixtures.ORG_ID)
        },
        Case("listOfferedEntitlementTemplates", "GET /api/bloem/v2/admin/platform/entitlement-templates", PlatformFixtures.templates(PlatformFixtures.template("basic", "Basic", 3))) {
            listOfferedEntitlementTemplates()
        },
        Case("previewOrganizationEntitlement", "POST /api/bloem/v2/admin/platform/organizations/{id}/entitlement/dry-run", PlatformFixtures.dryRun()) {
            previewOrganizationEntitlement(PlatformFixtures.ORG_ID, "premium", 2)
        },
        Case("applyOrganizationEntitlement", "POST /api/bloem/v2/admin/platform/organizations/{id}/entitlement/apply", PlatformFixtures.applied()) {
            applyOrganizationEntitlement(PlatformFixtures.ORG_ID, "premium", 2, "token", "key-1")
        },
        Case("getPlatformCapabilities", "GET /api/bloem/v2/capabilities", """{"feature_tokens":[],"features":{"organization_memberships":true}}""") { getPlatformCapabilities() },

        Case("listUsers", "GET /api/v2/admin/users") { listUsers() },
        Case("getUserWithValidator", "GET /api/v2/admin/users/{id}", user) { getUserWithValidator("1") },
        Case("createUser", "POST /api/v2/admin/users", user) { createUser(CreateUserRequest("alice", "a@example.test", "pw", "user", true)) },
        Case("updateUser", "PUT /api/v2/admin/users/{id}", user) { updateUser("1", buildJsonObject { put("enabled", false) }, "\"v1\"") },
        Case("deleteUser", "DELETE /api/v2/admin/users/{id}", "") { deleteUser("1", "\"v1\"") },
        Case("listLoginSessions", "GET /api/v2/admin/users/{user_id}/login-sessions") { listLoginSessions("1") },
        Case("revokeLoginSession", "DELETE /api/v2/admin/users/{user_id}/login-sessions/{session_id}", "") { revokeLoginSession("1", "ls1") },
        Case("revokeAllLoginSessions", "DELETE /api/v2/admin/users/{user_id}/login-sessions", V2Fixtures.loginSessionsRevoked(1)) { revokeAllLoginSessions("1") },
        Case("listUserDevices", "GET /api/v2/admin/users/{id}/devices") { listUserDevices("1") },
        Case("passwordResetCapabilities", "GET /api/v2/admin/users/capabilities", V2Fixtures.adminAccountCapabilities()) { passwordResetCapabilities() },
        Case("requestPasswordReset", "POST /api/v2/admin/users/{id}/password-reset", """{"delivery":"email","delivery_status":"sent","expires_at":"2026-11-06T00:00:00Z"}""") { requestPasswordReset("1") },
        Case("requestPasswordReset", "POST /api/v2/admin/users/{id}/password-reset", """{"delivery":"link","delivery_status":"not_requested","expires_at":"2026-11-06T00:00:00Z","reset_url":"https://x.test/reset-password#abc"}""") { requestPasswordReset("1", "link") },
        Case("getAccountMe", "GET /api/v2/account/me", """{"id":"1","username":"alice","email":"a@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""") { getAccountMe() },
        Case("getUserCapabilities", "GET /api/v2/admin/users/capabilities", V2Fixtures.adminAccountCapabilities()) { getUserCapabilities() },
        Case("listUserProfiles", "GET /api/v2/admin/users/{id}/profiles") { listUserProfiles("1") },
        Case("createUserProfile", "POST /api/v2/admin/users/{id}/profiles", "") { createUserProfile("1", "Maya", "1234") },
        Case("renameUserProfile", "PATCH /api/v2/admin/users/{id}/profiles/{profile_id}", "") { renameUserProfile("1", "p1", "Maya") },
        Case("setUserProfilePin", "PATCH /api/v2/admin/users/{id}/profiles/{profile_id}", "") { setUserProfilePin("1", "p1", "1234") },
        Case("removeUserProfilePin", "PATCH /api/v2/admin/users/{id}/profiles/{profile_id}", "") { removeUserProfilePin("1", "p1") },
        Case("deleteUserProfile", "DELETE /api/v2/admin/users/{id}/profiles/{profile_id}", "") { deleteUserProfile("1", "p1") },
        Case("listUserLiveSessions", "GET /api/v2/admin/sessions") { listUserLiveSessions("1") },
        Case("getUserWatchSummary", "GET /api/v2/admin/users/{id}/watch-summary", """{"days":30,"since":"2026-01-01T00:00:00Z","plays":3,"completed_plays":2,"watched_seconds":120.0,"last_played_at":null}""") { getUserWatchSummary("1", 30) },
        Case("listUserIps", "GET /api/v2/admin/users/{id}/ips") { listUserIps("1", 30, 50) },
        Case("listUserDownloads", "GET /api/v2/admin/users/{id}/downloads") { listUserDownloads("1", "c1", 50) },
        Case("listAccessGroups", "GET /api/v2/admin/access-groups") { listAccessGroups() },
        Case("getUserPolicyDefaults", "GET /api/v2/admin/users/policy-defaults", """{"admin":{"library_ids":null},"ungrouped":{"library_ids":["1"]}}""") { getUserPolicyDefaults() },
        Case("getSettingsContract", "GET /api/v2/settings/contract", """{"api_version":1,"revision":28,"definitions":[]}""") { getSettingsContract() },
        Case("listUserSettingValues", "GET /api/v2/admin/users/{id}/settings/values") { listUserSettingValues("1") },
        Case("setUserSettingValue", "PUT /api/v2/admin/users/{id}/settings/values/{key}", """{"key":"ui.theme","scope":"profile","value":"dark","revision":2}""") {
            setUserSettingValue("1", "ui.theme", SettingIdentity("profile", profileId = "p1"), JsonPrimitive("dark"))
        },
        Case("setUserSettingValue", "PUT /api/v2/admin/users/{id}/settings/values/{key}", """{"key":"player.speed","scope":"profile_library","value":1.5,"revision":2}""") {
            setUserSettingValue("1", "player.speed", SettingIdentity("profile_library", profileId = "p1", libraryId = "3"), JsonPrimitive(1.5))
        },
        Case("deleteUserSettingValue", "DELETE /api/v2/admin/users/{id}/settings/values/{key}", "") {
            deleteUserSettingValue("1", "ui.theme", SettingIdentity("profile_device", profileId = "p1", deviceId = "tv 1"))
        },
        Case("getUserDownloadSummary", "GET /api/v2/admin/users/{id}/downloads/summary", """{"total":1,"completed":1,"in_progress":0,"failed":0,"revoked":0,"total_bytes":10,"devices":1,"monitored_series":0}""") { getUserDownloadSummary("1") },

        Case("login", "POST /api/v2/auth/login", V2Fixtures.TOKEN_PAIR) { login(this, "admin", "pw") },
        Case("refresh", "POST /api/v2/auth/refresh", V2Fixtures.REFRESHED_TOKENS) { refresh(this, "r") },
        Case("getServerIdentity", "GET /api/v2/system/identity", V2Fixtures.SERVER_IDENTITY) { getServerIdentity(this) },
        Case("listAuthProviders", "GET /api/v2/auth/providers", V2Fixtures.AUTH_PROVIDERS) { listAuthProviders(this) },
        Case("completeOAuthLogin", "POST /api/v2/auth/oauth/complete", V2Fixtures.OAUTH_COMPLETION) { completeOAuthLogin(this, "c", "v") },
        Case("registerPushDevice", "POST /api/bloem/v2/admin/push/devices", """{"device_id":"dev1","display_token":"bad_x","kinds":[]}""") {
            registerPushDevice(PushRegisterRequest("install-0001", "android", "f".repeat(64), listOf("admin.scan_failed")))
        },
        Case("setPushKinds", "PATCH /api/bloem/v2/admin/push/devices/{device_id}/kinds", """{"kinds":[]}""") {
            setPushKinds("dev1", mapOf("admin.scan_failed" to false))
        },
        Case("deletePushDevice", "DELETE /api/bloem/v2/admin/push/devices/{device_id}", "") { deletePushDevice("dev1") },
        Case("setPushWidgets", "PUT /api/bloem/v2/admin/push/devices/{device_id}/widgets", """{"widgets_placed":true,"channels":["nodes"]}""") {
            setPushWidgets("dev1", true, listOf("nodes"))
        }
    )

    private fun segmentsMatch(template: String, actual: String): Boolean {
        val t = template.trim('/').split('/')
        val a = actual.trim('/').split('/')
        return t.size == a.size && t.zip(a).all { (x, y) -> (x.startsWith("{") && y.isNotEmpty()) || x == y }
    }

    /** The most specific template (most literal segments) that fits the method and path. */
    private fun findOperation(method: String, path: String): String? =
        contract.keys.filter { template ->
            contract.getValue(template).jsonObject.containsKey(method.lowercase()) && segmentsMatch(template, path)
        }.minByOrNull { template -> template.count { it == '{' } }

    @Test
    fun `every api function sends requests the contract declares`() = runTest {
        val violations = mutableListOf<String>()
        for (case in cases) {
            var seen = 0
            val engine = MockEngine { request ->
                seen++
                val method = request.method.value
                val path = request.url.encodedPath
                val label = "${case.function}: $method $path"
                val template = findOperation(method, path)
                if (case.operation.startsWith("EXEMPT ")) {
                    assertEquals("exempt route is not in the v2 contract: $label", null, template)
                } else if (template == null) {
                    violations += "$label is not an operation in the contract"
                } else {
                    if ("$method $template" != case.operation) violations += "$label matched $method $template, expected ${case.operation}"
                    val operation = contract.getValue(template).jsonObject.getValue(method.lowercase()).jsonObject
                    val declared = operation.getValue("query").jsonArray.map { it.jsonPrimitive.content }.toSet()
                    val required = operation.getValue("required_query").jsonArray.map { it.jsonPrimitive.content }
                    val sent = request.url.parameters.names()
                    (sent - declared).forEach { violations += "$label sends undeclared query parameter '$it' (declared: $declared)" }
                    (required - sent).forEach { violations += "$label is missing required query parameter '$it'" }
                    val bounds = operation["query_bounds"]?.jsonObject ?: JsonObject(emptyMap())
                    for ((name, limits) in bounds) {
                        val value = request.url.parameters[name]?.toDoubleOrNull() ?: continue
                        val min = limits.jsonObject["min"]?.jsonPrimitive?.doubleOrNull
                        val max = limits.jsonObject["max"]?.jsonPrimitive?.doubleOrNull
                        if (min != null && value < min) violations += "$label sends $name=${request.url.parameters[name]}, below the declared minimum $min"
                        if (max != null && value > max) violations += "$label sends $name=${request.url.parameters[name]}, above the declared maximum $max"
                    }
                    if (operation.getValue("body_required").jsonPrimitive.booleanOrNull == true) {
                        val text = String(request.body.toByteArray())
                        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                        val nonEmpty = json != null && (json !is JsonPrimitive || json.content.isNotEmpty())
                        if (!nonEmpty) violations += "$label requires a JSON body but sent '$text'"
                        if (request.body.contentType?.match(ContentType.Application.Json) != true) {
                            violations += "$label requires a JSON body but sent Content-Type ${request.body.contentType}"
                        }
                    }
                }
                val success = if (case.operation.startsWith("EXEMPT ") || template == null) "200"
                else contract.getValue(template).jsonObject.getValue(method.lowercase()).jsonObject
                    .getValue("success").jsonArray.first().jsonPrimitive.content
                val status = HttpStatusCode.fromValue(success.toInt())
                respond(
                    if (status == HttpStatusCode.NoContent) "" else case.response,
                    status,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val client = ApiClient("https://example.test", null, engine)
            try {
                case.call(client)
            } finally {
                client.close()
            }
            if (seen == 0) violations += "${case.function} sent no request"
        }
        assertTrue("Contract violations:\n" + violations.joinToString("\n") { "  $it" }, violations.isEmpty())
    }

    @Test
    fun `events routes are declared by the contract`() {
        assertEquals("POST /api/v2/events/ws-ticket", "POST " + findOperation("POST", EVENTS_TICKET_PATH))
        assertEquals("GET /api/v2/events/ws", "GET " + findOperation("GET", EVENTS_SOCKET_PATH))
    }

    @Test
    fun `every public api function has a contract case`() {
        val sourceRoot = listOf("app/src/main/java", "src/main/java").map(::File).first { it.isDirectory }
        val apiDir = File(sourceRoot, "com/bloemstudios/admin/network/api")
        val extensions = Regex("""^suspend fun ApiClient\.(\w+)""")
        val functions = apiDir.listFiles { f -> f.extension == "kt" }!!
            .flatMap { file -> file.readLines().mapNotNull { extensions.find(it)?.groupValues?.get(1) } }
            .toMutableSet()
        File(sourceRoot, "com/bloemstudios/admin/auth/AuthApi.kt").readLines()
            .mapNotNull { Regex("""^suspend fun (\w+)\(""").find(it)?.groupValues?.get(1) }
            .forEach { functions += it }
        val covered = cases.map { it.function }.toSet()
        assertEquals("Public API functions without a case in ApiContractTest", emptySet<String>(), functions - covered)
        assertEquals("Cases for functions that no longer exist", emptySet<String>(), covered - functions)
    }
}
