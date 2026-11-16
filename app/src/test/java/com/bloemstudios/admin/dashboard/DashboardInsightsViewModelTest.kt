package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections

class DashboardInsightsViewModelTest {
    private val problem =
        """{"type":"https://siloserver.org/docs/api/v2/problems/service_unavailable","title":"Service Unavailable","status":503,"detail":"Fixture down.","instance":"urn:silo:request:fixture"}"""

    private fun body(path: String): String? = when (path) {
        "/api/v2/admin/stats" -> V2Fixtures.ADMIN_STATS
        "/api/v2/admin/system/resources" -> V2Fixtures.SYSTEM_RESOURCES
        "/api/v2/admin/server/status" -> V2Fixtures.SERVER_STATUS
        "/api/v2/admin/system/build" -> """{"available":true,"build_number":12,"dirty":true,"display":"v1.4","revision":"abc"}"""
        "/api/v2/admin/nodes" -> """{"items":[{"id":"n1","name":"Node","enabled":true,"healthy":true},{"id":"n2","name":"Old","enabled":false,"healthy":false}]}"""
        "/api/v2/admin/stats/playback-activity" ->
            """{"hours":24,"bucket_seconds":3600,"buckets":[{"hour":"2026-11-13T09:00:00Z","direct":3,"remux":1,"transcode":2}],"reliability":{"sessions_started":6,"transcode_starts":2,"finalized_sessions":4,"completed_sessions":3,"completion_rate":0.75,"unique_profiles":2},"profiles_active_24h":2}"""
        "/api/v2/admin/stats/timeseries" ->
            """{"resolution_seconds":60,"oldest_sample_at":"2026-11-12T00:00:00Z","points":[{"t":"2026-11-13T09:00:00Z","streams":1,"egress_kbps":2000,"download_egress_kbps":0}]}"""
        "/api/v2/admin/stats/top-activity" ->
            """{"days":7,"limit":5,"titles":[{"media_item_id":"m1","title":"The River","media_type":"movie","plays":4,"total_seconds":7200}],"profiles":[{"user_id":"u1","username":"alex","profile_id":"p1","profile_name":"Alex","plays":4,"total_seconds":7200}]}"""
        "/api/v2/libraries" -> """{"items":[{"id":"l1","name":"Films","type":"movies","paths":["/m"],"enabled":true}]}"""
        "/api/v2/admin/users" -> """{"items":[${V2Fixtures.adminUser()}]}"""
        "/api/v2/admin/playback-history" ->
            """{"items":[{"session_id":"s1","user_id":"u1","username":"alex","media_title":"The River","media_type":"movie","watched_seconds":60.0,"duration_seconds":5400.0,"completed":false,"started_at":"2026-11-13T09:00:00Z"}]}"""
        else -> null
    }

    private fun client(requests: MutableList<String>, failing: Set<String> = emptySet(), failingNow: () -> Set<String> = { failing }) = ApiClient("https://example.test", null, MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val content = body(path)
        if (path in failingNow() || content == null) respond(problem, HttpStatusCode.ServiceUnavailable, json) else respond(content, HttpStatusCode.OK, json)
    })

    @Test fun `one load fills every section`() = runTest {
        val client = client(Collections.synchronizedList(mutableListOf()))
        val model = DashboardViewModel()
        try {
            model.load(client)
            val insights = model.insights.value
            assertEquals("12", insights.build.value?.build_number?.toString())
            assertEquals(1, insights.nodes.value?.count { it.enabled })
            assertNotNull(insights.status.value)
            assertEquals(2, insights.activity.value?.profiles_active_24h)
            assertEquals(6, insights.activity.value?.totalStarts)
            assertEquals(1, insights.egressNow.value?.points?.size)
            assertEquals(1, insights.egress24h.value?.points?.size)
            assertEquals("The River", insights.top.value?.titles?.single()?.title)
            assertEquals("Films", insights.libraries.value?.single()?.name)
            assertEquals(1, insights.users.value?.size)
            assertEquals("alex", insights.recent.value?.single()?.username)
        } finally { client.close() }
    }

    @Test fun `a failing section keeps its last good value beside the failure`() = runTest {
        var failing = emptySet<String>()
        val client = client(Collections.synchronizedList(mutableListOf()), failingNow = { failing })
        val model = DashboardViewModel()
        try {
            model.load(client)
            assertNotNull(model.insights.value.top.value)
            assertNotNull(model.insights.value.nodes.value)
            failing = setOf("/api/v2/admin/stats/top-activity", "/api/v2/admin/nodes")
            model.load(client, forceRefresh = true)
            val insights = model.insights.value
            assertNotNull("the last good list stays beside the failure", insights.top.value)
            assertNotNull(insights.top.error)
            assertNotNull(insights.nodes.value)
            assertNotNull(insights.nodes.error)
            assertNull(insights.libraries.error)
            assertNotNull(insights.libraries.value)
            failing = emptySet()
            model.load(client, forceRefresh = true)
            assertNull("a good refresh clears the failure", model.insights.value.top.error)
        } finally { client.close() }
    }

    @Test fun `a failing section alone leaves the rest loaded`() = runTest {
        val client = client(Collections.synchronizedList(mutableListOf()), failing = setOf("/api/v2/libraries"))
        val model = DashboardViewModel()
        try {
            model.load(client)
            assertNull(model.insights.value.libraries.value)
            assertNotNull(model.insights.value.libraries.error)
            assertNotNull(model.insights.value.users.value)
            assertNotNull(model.stats.value)
            assertNull(model.errorMessage.value)
        } finally { client.close() }
    }

    @Test fun `slow sections are read every few minutes and fast ones every poll`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        var now = Instant.parse("2026-11-13T10:00:00Z")
        model.clock = { now }
        val slow = listOf("/api/v2/admin/system/build", "/api/v2/admin/stats/top-activity", "/api/v2/libraries", "/api/v2/admin/users")
        try {
            model.load(client)
            assertTrue(slow.all { path -> requests.count { it == path } == 1 })
            now = now.plusSeconds(30)
            model.load(client)
            assertTrue("slow sections wait", slow.all { path -> requests.count { it == path } == 1 })
            assertEquals("fast sections follow every poll", 2, requests.count { it == "/api/v2/admin/nodes" })
            assertEquals(2, requests.count { it == "/api/v2/admin/server/status" })
            now = now.plusSeconds(301)
            model.load(client)
            assertTrue("slow sections are due after five minutes", slow.all { path -> requests.count { it == path } == 2 })
            model.load(client, forceRefresh = true)
            assertTrue("a manual refresh reads everything", slow.all { path -> requests.count { it == path } == 3 })
        } finally { client.close() }
    }

    @Test fun `a failed slow section is retried on the next poll`() = runTest {
        var failing = setOf("/api/v2/libraries")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests, failingNow = { failing })
        val model = DashboardViewModel()
        model.clock = { Instant.parse("2026-11-13T10:00:00Z") }
        try {
            model.load(client)
            assertNotNull(model.insights.value.libraries.error)
            failing = emptySet()
            model.load(client)
            assertNotNull(model.insights.value.libraries.value)
            assertNull(model.insights.value.libraries.error)
        } finally { client.close() }
    }

    @Test fun `manual refresh asks the insight endpoints to recompute`() = runTest {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            queries += request.url.encodedPath + "?" + request.url.parameters.names().sorted().joinToString(",")
            val content = body(request.url.encodedPath) ?: "{}"
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            DashboardViewModel().load(client, forceRefresh = true)
            assertTrue(queries.toString(), "/api/v2/admin/stats/playback-activity?hours,refresh" in queries)
            assertTrue(queries.toString(), "/api/v2/admin/stats/top-activity?days,limit,refresh" in queries)
        } finally { client.close() }
    }
}
