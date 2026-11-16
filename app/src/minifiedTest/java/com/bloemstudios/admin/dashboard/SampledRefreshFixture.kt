package com.bloemstudios.admin.dashboard

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/** Test variant only: Ktor/inline ViewModel APIs execute in the same minification unit. */
class SampledRefreshFixture(private val individual: Boolean) {
    val statsReads = AtomicInteger()
    val hostReads = AtomicInteger()
    val held = AtomicInteger()
    val canceled = AtomicInteger()
    val manualStatsReads = AtomicInteger()
    private val releaseOldReads = CompletableDeferred<Unit>()
    private val heldRound = if (individual) 2 else 1
    lateinit var model: DashboardViewModel
        private set
    private fun problem(status: Int, slug: String, title: String) =
        """{"type":"https://siloserver.org/docs/api/v2/problems/$slug","title":"$title","status":$status,"detail":"Fixture $title.","instance":"urn:silo:request:fixture"}"""
    private val api = ApiClient("https://fixture.invalid", null, MockEngine { request ->
        val path = request.url.encodedPath
        val round = when (path) {
            "/api/v2/admin/stats" -> {
                if (request.url.parameters["refresh"] == "true") manualStatsReads.incrementAndGet()
                statsReads.incrementAndGet()
            }
            "/api/v2/admin/system/resources" -> hostReads.incrementAndGet()
            "/api/v2/admin/sessions" -> return@MockEngine respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else -> return@MockEngine respond(problem(404, "not_found", "Not Found"), HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        if (round == heldRound) {
            held.incrementAndGet()
            try { releaseOldReads.await() }
            catch (e: CancellationException) { canceled.incrementAndGet(); throw e }
        } else if (individual && (round == 1 || round == heldRound + 1)) {
            // Automatic samples fail so the production screen's explicit Retry remains available.
            return@MockEngine respond(problem(503, "service_unavailable", "Service Unavailable"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val body = if (path.endsWith("/resources")) """{"available":true,"stale":false,"sampled_at":"2026-11-01T00:00:00Z","system":{"cpu_pct":12.5,"mem_used_mb":1024,"mem_total_mb":4096,"net_rx_bps":800.0,"net_tx_bps":1600.0,"cores":4,"load1":0.5}}""" else
            """{"total_items":42,"total_files":42,"total_users":1,"total_movies":42,"total_movie_files":42,"total_shows":0,"total_show_files":0,"advisory_titles":0,"active_streams":99,"total_storage_bytes":0,"watch_providers":[]}"""
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Composable
    fun Render() {
        if (individual) {
            val current = viewModel<DashboardViewModel>()
            SideEffect { model = current }
            DashboardScreen(api)
        } else {
            val current = remember { DashboardViewModel() }
            SideEffect { model = current }
            AggregateRefreshSurface(emptyList(), onRefresh = { current.load(api, true) }) { Text("Sample fixture") }
        }
    }

    fun releaseHeldReads() { releaseOldReads.complete(Unit) }
    fun close() { releaseHeldReads(); if (::model.isInitialized) model.close(); api.close() }
}
