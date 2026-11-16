package com.bloemstudios.admin.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Test variant only: the real dashboard screen and view model against a mock engine that answers the chart
 * endpoints for whichever window is asked, so range switching is exercised end to end.
 */
class TrendChartsFixture {
    /** Every request as `path` or `path hours=N`, in the order made. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Request keys (as in [requests]) answered with a 503. */
    val failing = AtomicReference<Set<String>>(emptySet())
    lateinit var model: DashboardViewModel
        private set

    private fun problem() =
        """{"type":"https://siloserver.org/docs/api/v2/problems/service_unavailable","title":"Service Unavailable","status":503,"detail":"Fixture down.","instance":"urn:silo:request:fixture"}"""

    private fun timeseries(hours: Int): String {
        val resolution = when { hours <= 2 -> 60; hours <= 48 -> 300; hours <= 336 -> 1800; else -> 7200 }
        val to = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val from = to.minusSeconds(hours * 3600L)
        val at = { back: Int -> to.minusSeconds(back.toLong() * resolution).toString() }
        // Peak 40 Mbps, 3 streams, with a gap before the newest sample.
        return """{"resolution_seconds":$resolution,"from":"$from","to":"$to","oldest_sample_at":"$from","egress_bytes":1500000000,"points":[
            {"t":"${at(4)}","streams":1,"egress_kbps":2000,"download_egress_kbps":500},
            {"t":"${at(3)}","streams":3,"egress_kbps":40000,"download_egress_kbps":0},
            {"t":"${at(0)}","streams":2,"egress_kbps":12000,"download_egress_kbps":0}]}"""
    }

    private fun playback(hours: Int): String {
        val daily = hours > 48
        val step = if (daily) 86_400L else 3600L
        val to = Instant.now()
        val bucket = Instant.ofEpochSecond(Math.floorDiv(to.epochSecond, step) * step)
        return """{"hours":$hours,"bucket_seconds":$step,"from":"${to.minusSeconds(hours * 3600L)}","to":"$to","buckets":[
            {"hour":"${bucket.minusSeconds(step)}","direct":2,"remux":1,"transcode":0},
            {"hour":"$bucket","direct":3,"remux":1,"transcode":2}],
            "reliability":{"finalized_sessions":4,"completed_sessions":3,"completion_rate":0.75},"profiles_active_24h":2}"""
    }

    private val api = ApiClient("https://fixture.invalid", null, MockEngine { request ->
        val path = request.url.encodedPath
        val hours = request.url.parameters["hours"]?.toIntOrNull()
        val key = if (hours == null) path else "$path hours=$hours"
        requests += key
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (key in failing.get()) return@MockEngine respond(problem(), HttpStatusCode.ServiceUnavailable, json)
        val body = when (path) {
            "/api/v2/admin/stats/timeseries" -> timeseries(hours ?: 24)
            "/api/v2/admin/stats/playback-activity" -> playback(hours ?: 24)
            "/api/v2/admin/sessions" -> """{"items":[]}"""
            else -> return@MockEngine respond(problem(), HttpStatusCode.NotFound, json)
        }
        respond(body, HttpStatusCode.OK, json)
    })

    fun count(key: String): Int = synchronized(requests) { requests.count { it == key } }

    @Composable
    fun Render() {
        val current = viewModel<DashboardViewModel>()
        SideEffect { model = current }
        DashboardScreen(api)
    }

    fun close() { if (::model.isInitialized) model.close(); api.close() }
}
