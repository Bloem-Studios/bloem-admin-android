package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class JobSubmissionTest {
    @Test fun `repeated run taps submit one operation until it resolves`() = runTest {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val posts = AtomicInteger()
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            if (req.method == HttpMethod.Post) { posts.incrementAndGet(); entered.complete(Unit); gate.await(); respond("", HttpStatusCode.OK) }
            else respond(V2Fixtures.adminTasksPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = JobsViewModel(); val task = AdminTask("scan", "Scan", "", "library", "idle", 0.0, "process")
        val first = launch { vm.run(task, client) }; entered.await()
        val second = launch { vm.run(task, client) }
        try {
            withContext(Dispatchers.Default) { withTimeout(1_000) { second.join() } }
            assertEquals(1, posts.get())
        } finally { gate.complete(Unit); first.join(); second.join(); client.close() }
    }
}
