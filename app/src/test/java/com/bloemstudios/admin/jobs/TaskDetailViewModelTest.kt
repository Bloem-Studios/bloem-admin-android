package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDetailViewModelTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun run(id: String, status: String = "completed", extra: String = "") =
        """{"id":"$id","task_key":"database_maintenance","started_at":"2026-11-14T02:00:00.000Z","completed_at":"2026-11-14T02:03:00.000Z","status":"$status","duration_ms":180000$extra}"""

    private val failedExtra = ""","steps":[{"key":"a","name":"Vacuum","status":"completed"},{"key":"b","name":"Reindex","status":"failed"}],"error_message":"Task failed. Inspect administrator diagnostics for details.""""

    private fun history(vararg runs: String, next: String? = null) =
        """{"items":[${runs.joinToString(",")}],"page":{"has_more":${next != null}${if (next != null) ",\"next_cursor\":\"$next\"" else ""}}}"""

    private fun client(requests: MutableList<String> = mutableListOf(), handler: (String, HttpMethod) -> Pair<HttpStatusCode, String>): ApiClient =
        ApiClient("https://server.test", "token", MockEngine { req ->
            requests += "${req.method.value} ${req.url.encodedPath}${req.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }}"
            val (status, body) = handler(req.url.encodedPath, req.method)
            respond(body, status, jsonHeaders)
        })

    @Test fun `opening the page reads the task and its first page of runs`() = runTest {
        val seen = mutableListOf<String>()
        val api = client(seen) { path, _ ->
            when {
                path.endsWith("/history") -> HttpStatusCode.OK to history(run("2", "failed", failedExtra), run("1"), next = "c2")
                else -> HttpStatusCode.OK to V2Fixtures.adminTask("database_maintenance")
            }
        }
        val vm = TaskDetailViewModel()
        vm.open(api, "database_maintenance")
        assertEquals("database_maintenance", vm.task.value?.key)
        assertEquals(listOf("2", "1"), vm.runs.value.map { it.id })
        assertEquals(listOf("Reindex"), failedSteps(vm.runs.value.first().steps))
        assertTrue(vm.hasMoreRuns.value)
        assertTrue(seen.any { it == "GET /api/v2/admin/tasks/database_maintenance" })
        assertTrue(seen.any { it.startsWith("GET /api/v2/admin/tasks/database_maintenance/history?") })
        api.close()
    }

    @Test fun `older runs are loaded with the cursor and appended`() = runTest {
        val seen = mutableListOf<String>()
        val api = client(seen) { path, _ ->
            when {
                path.endsWith("/history") -> HttpStatusCode.OK to (if (seen.last().contains("cursor=c2")) history(run("0")) else history(run("1"), next = "c2"))
                else -> HttpStatusCode.OK to V2Fixtures.adminTask("database_maintenance")
            }
        }
        val vm = TaskDetailViewModel()
        vm.open(api, "database_maintenance")
        vm.loadMoreRuns(api)
        assertEquals(listOf("1", "0"), vm.runs.value.map { it.id })
        assertFalse(vm.hasMoreRuns.value)
        api.close()
    }

    @Test fun `a task the server no longer has is reported as gone`() = runTest {
        val api = client { _, _ -> HttpStatusCode.NotFound to V2Fixtures.problem(404, "not_found", "Task not found") }
        val vm = TaskDetailViewModel()
        vm.open(api, "gone")
        assertTrue(vm.notFound.value)
        assertNull(vm.task.value)
        api.close()
    }

    @Test fun `a failed history read keeps the task and says the runs did not load`() = runTest {
        val api = client { path, _ ->
            if (path.endsWith("/history")) HttpStatusCode.InternalServerError to V2Fixtures.problem(500, "internal_error", "Something went wrong")
            else HttpStatusCode.OK to V2Fixtures.adminTask("database_maintenance")
        }
        val vm = TaskDetailViewModel()
        vm.open(api, "database_maintenance")
        assertNotNull(vm.task.value)
        assertNotNull(vm.runsError.value)
        assertTrue(vm.runs.value.isEmpty())
        api.close()
    }

    @Test fun `a refresh re-reads the task without losing the runs already shown`() = runTest {
        var state = "idle"
        val api = client { path, _ ->
            if (path.endsWith("/history")) HttpStatusCode.OK to history(run("1"))
            else HttpStatusCode.OK to V2Fixtures.adminTask("database_maintenance", state = state)
        }
        val vm = TaskDetailViewModel()
        vm.open(api, "database_maintenance")
        state = "running"
        vm.refreshTask(api, "database_maintenance")
        assertEquals("running", vm.task.value?.state)
        assertEquals(listOf("1"), vm.runs.value.map { it.id })
        api.close()
    }

    @Test fun `run posts to the task and reloads it`() = runTest {
        val seen = mutableListOf<String>()
        val api = client(seen) { _, _ -> HttpStatusCode.OK to V2Fixtures.adminTask("database_maintenance") }
        val vm = TaskDetailViewModel()
        vm.run(api, "database_maintenance")
        assertEquals("POST /api/v2/admin/tasks/database_maintenance/run", seen.first())
        assertTrue(seen.any { it == "GET /api/v2/admin/tasks/database_maintenance" })
        api.close()
    }
}
