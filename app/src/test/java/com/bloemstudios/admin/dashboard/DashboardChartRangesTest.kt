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

/** Which windows the trend charts read, when, and what a failed read leaves behind. */
class DashboardChartRangesTest {
    private val problem =
        """{"type":"https://siloserver.org/docs/api/v2/problems/service_unavailable","title":"Service Unavailable","status":503,"detail":"Fixture down.","instance":"urn:silo:request:fixture"}"""

    private fun series(hours: String) =
        """{"resolution_seconds":60,"from":"2026-11-13T09:00:00Z","to":"2026-11-13T09:01:00Z","oldest_sample_at":"2026-11-12T00:00:00Z","egress_bytes":1500000,"points":[{"t":"2026-11-13T09:00:00Z","streams":$hours,"egress_kbps":2000,"download_egress_kbps":500}]}"""

    private fun activity(hours: String) =
        """{"hours":$hours,"bucket_seconds":3600,"to":"2026-11-13T09:30:00Z","buckets":[{"hour":"2026-11-13T09:00:00Z","direct":3,"remux":1,"transcode":2}],"reliability":{},"profiles_active_24h":2}"""

    private fun body(path: String, hours: String?): String? = when (path) {
        "/api/v2/admin/stats" -> V2Fixtures.ADMIN_STATS
        "/api/v2/admin/system/resources" -> V2Fixtures.SYSTEM_RESOURCES
        "/api/v2/admin/server/status" -> V2Fixtures.SERVER_STATUS
        "/api/v2/admin/system/build" -> """{"available":true,"build_number":12,"display":"v1.4"}"""
        "/api/v2/admin/nodes" -> """{"items":[]}"""
        "/api/v2/admin/stats/playback-activity" -> activity(hours ?: "24")
        "/api/v2/admin/stats/timeseries" -> series(hours ?: "24")
        "/api/v2/admin/stats/top-activity" -> """{"days":7,"limit":5,"titles":[],"profiles":[]}"""
        "/api/v2/libraries" -> """{"items":[]}"""
        "/api/v2/admin/users" -> """{"items":[]}"""
        "/api/v2/admin/playback-history" -> """{"items":[]}"""
        else -> null
    }

    /** Requests as `path hours=N`; [failing] makes a path-and-hours pair answer 503. */
    private fun client(requests: MutableList<String>, failing: () -> Set<String> = { emptySet() }) = ApiClient("https://example.test", null, MockEngine { request ->
        val path = request.url.encodedPath
        val hours = request.url.parameters["hours"]
        requests += if (hours == null) path else "$path hours=$hours"
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val content = body(path, hours)
        if (content == null || "$path hours=$hours" in failing()) respond(problem, HttpStatusCode.ServiceUnavailable, json) else respond(content, HttpStatusCode.OK, json)
    })

    private val timeseries = "/api/v2/admin/stats/timeseries"
    private val playback = "/api/v2/admin/stats/playback-activity"

    @Test fun `every chart starts on the day and a plain load reads only the day and the hour`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            assertEquals(ChartRanges(), model.ranges.value)
            model.load(client)
            assertEquals(setOf("$timeseries hours=1", "$timeseries hours=24"), requests.filter { it.startsWith(timeseries) }.toSet())
            assertEquals(listOf("$playback hours=24"), requests.filter { it.startsWith(playback) })
            assertTrue(model.insights.value.rangedSeries.isEmpty() && model.insights.value.rangedActivity.isEmpty())
        } finally { client.close() }
    }

    @Test fun `choosing a week reads it once and the other charts keep their window`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            model.load(client)
            requests.clear()
            model.selectRange(ChartKind.Egress, ChartRange.Week)
            assertEquals(ChartRange.Week, model.ranges.value.egress)
            assertEquals(ChartRange.Day, model.ranges.value.streams)
            model.loadCharts(client)
            assertEquals(listOf("$timeseries hours=168"), requests.toList())
            val week = model.insights.value.seriesFor(ChartRange.Week)
            assertNotNull(week.value)
            assertEquals(1_500_000.0, week.value!!.egress_bytes!!, 0.0)
            // The day is still the regular read.
            assertNotNull(model.insights.value.seriesFor(ChartRange.Day).value)
            model.loadCharts(client)
            assertEquals("a window already read is not read again", 1, requests.size)
        } finally { client.close() }
    }

    @Test fun `two charts on the same window share one read`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            model.load(client)
            requests.clear()
            model.selectRange(ChartKind.Egress, ChartRange.Month)
            model.selectRange(ChartKind.Streams, ChartRange.Month)
            model.loadCharts(client)
            assertEquals(listOf("$timeseries hours=720"), requests.toList())
        } finally { client.close() }
    }

    @Test fun `the hour reuses the egress now read instead of asking again`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            model.load(client)
            requests.clear()
            model.selectRange(ChartKind.Streams, ChartRange.Hour)
            model.loadCharts(client)
            assertTrue(requests.toString(), requests.isEmpty())
            assertNotNull(model.insights.value.seriesFor(ChartRange.Hour).value)
        } finally { client.close() }
    }

    @Test fun `playback activity reads the window it shows`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            model.load(client)
            requests.clear()
            model.selectRange(ChartKind.Playback, ChartRange.Month)
            model.loadCharts(client)
            assertEquals(listOf("$playback hours=720"), requests.toList())
            assertEquals(720, model.insights.value.activityFor(ChartRange.Month).value?.hours)
            assertEquals("the profile tile keeps reading the 24 hours", 24, model.insights.value.activityFor(ChartRange.Day).value?.hours)
        } finally { client.close() }
    }

    @Test fun `a window read before the first load waits for it`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        try {
            model.selectRange(ChartKind.Egress, ChartRange.Week)
            model.loadCharts(client)
            assertTrue(requests.isEmpty())
            // The regular load reads the chosen window itself.
            model.load(client)
            assertEquals(1, requests.count { it == "$timeseries hours=168" })
            assertNotNull(model.insights.value.seriesFor(ChartRange.Week).value)
        } finally { client.close() }
    }

    @Test fun `a week follows the slow tier, not every poll`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        var now = Instant.parse("2026-11-13T10:00:00Z")
        model.clock = { now }
        try {
            model.selectRange(ChartKind.Egress, ChartRange.Week)
            model.load(client)
            now = now.plusSeconds(30)
            model.load(client)
            assertEquals(1, requests.count { it == "$timeseries hours=168" })
            now = now.plusSeconds(301)
            model.load(client)
            assertEquals(2, requests.count { it == "$timeseries hours=168" })
            model.load(client, forceRefresh = true)
            assertEquals(3, requests.count { it == "$timeseries hours=168" })
        } finally { client.close() }
    }

    @Test fun `an hour of playback is read every poll but a month of it is not`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests)
        val model = DashboardViewModel()
        var now = Instant.parse("2026-11-13T10:00:00Z")
        model.clock = { now }
        try {
            model.selectRange(ChartKind.Playback, ChartRange.Hour)
            model.load(client)
            now = now.plusSeconds(30)
            model.load(client)
            assertEquals(2, requests.count { it == "$playback hours=1" })
            model.selectRange(ChartKind.Playback, ChartRange.Month)
            model.load(client)
            now = now.plusSeconds(30)
            model.load(client)
            assertEquals(1, requests.count { it == "$playback hours=720" })
        } finally { client.close() }
    }

    @Test fun `a failed window keeps its last good value beside the failure and is read again`() = runTest {
        var failing = emptySet<String>()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = client(requests) { failing }
        val model = DashboardViewModel()
        try {
            model.selectRange(ChartKind.Egress, ChartRange.Week)
            model.load(client)
            assertNotNull(model.insights.value.seriesFor(ChartRange.Week).value)
            failing = setOf("$timeseries hours=168")
            model.load(client, forceRefresh = true)
            val failed = model.insights.value.seriesFor(ChartRange.Week)
            assertNotNull("the last good chart stays", failed.value)
            assertNotNull(failed.error)
            // The day's chart is a different read and is untouched.
            assertNull(model.insights.value.seriesFor(ChartRange.Day).error)
            failing = emptySet()
            model.loadCharts(client)
            assertNull("choosing the window again retries it", model.insights.value.seriesFor(ChartRange.Week).error)
        } finally { client.close() }
    }

    @Test fun `a window that fails on first read shows the failure and no chart`() = runTest {
        val client = client(Collections.synchronizedList(mutableListOf())) { setOf("$timeseries hours=168") }
        val model = DashboardViewModel()
        try {
            model.load(client)
            model.selectRange(ChartKind.Streams, ChartRange.Week)
            model.loadCharts(client)
            val week = model.insights.value.seriesFor(ChartRange.Week)
            assertNull(week.value)
            assertNotNull(week.error)
            assertTrue(week.hasContent)
        } finally { client.close() }
    }

    @Test fun `closing the model forgets the samples but not the chosen windows`() = runTest {
        val client = client(Collections.synchronizedList(mutableListOf()))
        val model = DashboardViewModel()
        try {
            model.selectRange(ChartKind.Egress, ChartRange.Week)
            model.load(client)
            model.close()
            assertTrue(model.insights.value.rangedSeries.isEmpty())
            assertEquals(ChartRange.Week, model.ranges.value.egress)
        } finally { client.close() }
    }
}
