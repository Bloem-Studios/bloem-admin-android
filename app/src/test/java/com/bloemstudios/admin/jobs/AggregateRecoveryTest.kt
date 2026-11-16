package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AggregateRecoveryTest {
    private val fast = AdminServer("fast", "https://fast.test", "Fast")
    private val slow = AdminServer("slow", "https://slow.test", "Slow")
    private val json = V2Fixtures.adminTasksPage(V2Fixtures.adminTask())

    @Test fun `healthy results are visible before slow server finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val a = ApiClient(fast.baseUrl, "token", MockEngine {
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val b = ApiClient(slow.baseUrl, "token", MockEngine {
            entered.complete(Unit); gate.await()
            respond(V2Fixtures.adminTasksPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = AggregateJobsViewModel()
        val job = launch { vm.load(listOf(fast, slow)) { if (it.id == fast.id) a else b } }
        try {
            entered.await()
            withContext(Dispatchers.Default) {
                withTimeout(1_000) { vm.sections.first { rows -> rows.any { it.server.id == fast.id && it.result.isSuccess } } }
            }
            assertFalse(job.isCompleted)
        } finally { gate.complete(Unit); job.join(); a.close(); b.close() }
    }

    @Test fun `healthy server run starts while another server poll is unresolved`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Unit>()
        val a = ApiClient(fast.baseUrl, "token", MockEngine { request ->
            if (request.method == HttpMethod.Post) { posted.complete(Unit); respond("", HttpStatusCode.OK) }
            else respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val b = ApiClient(slow.baseUrl, "token", MockEngine {
            entered.complete(Unit); gate.await()
            respond(V2Fixtures.adminTasksPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = AggregateJobsViewModel()
        val factory: (AdminServer) -> ApiClient = { if (it.id == fast.id) a else b }
        val poll = launch { vm.load(listOf(fast, slow), factory) }
        entered.await()
        val action = launch { vm.run(fast, AdminTask("scan", "Scan", "", "library", "idle", 0.0, "process"), factory) }
        try {
            withContext(Dispatchers.Default) { withTimeout(1_000) { posted.await() } }
            assertFalse(poll.isCompleted)
        } finally { gate.complete(Unit); poll.join(); action.join(); a.close(); b.close() }
    }
}
