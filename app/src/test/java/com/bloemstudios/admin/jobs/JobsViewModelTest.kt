package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getTask
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class JobsViewModelTest {
    @Test
    fun `load decodes tasks`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.adminTasksPage(V2Fixtures.adminTask("refresh_metadata", category = "metadata")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = JobsViewModel()

        viewModel.load(client)

        assertEquals(1, viewModel.tasks.value.size)
        assertEquals("refresh_metadata", viewModel.tasks.value.first().key)
    }

    @Test
    fun `run posts to run endpoint`() = runTest {
        // run() reloads tasks on success (quality bar: reload only on
        // success), so a second GET request follows the mutating POST.
        // Capture every request path and assert on the first one — the
        // mutating call this test is about — rather than a single var
        // that the reload's GET would silently overwrite.
        val capturedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            respond(content = V2Fixtures.adminTasksPage(), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = JobsViewModel()
        val target = AdminTask("refresh_metadata", "Refresh Metadata", "", "metadata", "idle", 0.0, "process")

        viewModel.run(target, client)

        assertEquals("/api/v2/admin/tasks/refresh_metadata/run", capturedPaths.first())
    }

    @Test
    fun `tasks list reads the v2 envelope`() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            respond(
                V2Fixtures.adminTasksPage(
                    V2Fixtures.adminTask("scan", state = "running"),
                    V2Fixtures.adminTask("cleanup", optional = """"next_run_at":"2026-11-06T03:00:00.000Z"""")
                ),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = JobsViewModel()

        viewModel.load(client)

        assertEquals(listOf("scan", "cleanup"), viewModel.tasks.value.map { it.key })
        assertEquals("running", viewModel.tasks.value[0].state)
        assertEquals(listOf(TaskTrigger("startup")), viewModel.tasks.value[0].triggers)
        assertEquals("process", viewModel.tasks.value[0].execution_scope)
        assertEquals("2026-11-06T03:00:00.000Z", viewModel.tasks.value[1].next_run_at)
        assertEquals(listOf("https://example.test/api/v2/admin/tasks"), requested)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `run and cancel post to v2`() = runTest {
        val calls = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            calls += request.method to request.url.encodedPath
            if (request.method == HttpMethod.Post) respond(V2Fixtures.adminTask("refresh_metadata", "running"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(V2Fixtures.adminTasksPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = JobsViewModel()
        val target = AdminTask("refresh_metadata", "Refresh Metadata", "", "metadata", "idle", 0.0, "process")

        viewModel.run(target, client)
        viewModel.cancel(target, client)

        assertEquals(
            listOf(
                HttpMethod.Post to "/api/v2/admin/tasks/refresh_metadata/run",
                HttpMethod.Get to "/api/v2/admin/tasks",
                HttpMethod.Post to "/api/v2/admin/tasks/refresh_metadata/cancel",
                HttpMethod.Get to "/api/v2/admin/tasks"
            ),
            calls
        )
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `getTask reads one AdminTask with its triggers`() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(V2Fixtures.adminTask("refresh_metadata", triggers = """[{"type":"interval","interval_ms":3600000},{"type":"startup"}]"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)

        val task = client.getTask("refresh_metadata")

        assertEquals("/api/v2/admin/tasks/refresh_metadata", path)
        assertEquals(listOf(TaskTrigger("interval", 3_600_000L), TaskTrigger("startup")), task.triggers)
    }

    @Test
    fun `run failure shows the Problem detail`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(409, "conflict", "Task is already running"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = JobsViewModel()

        viewModel.run(AdminTask("scan", "Scan", "", "library", "idle", 0.0, "process"), client)

        assertEquals(ErrorText.Raw("Task is already running"), viewModel.errorMessage.value)
    }
}
