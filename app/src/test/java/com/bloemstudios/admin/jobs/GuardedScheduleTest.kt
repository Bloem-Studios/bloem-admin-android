package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuardedScheduleTest {
    @Test fun `conflict preserves original validator until an explicit reread`() = runTest {
        val validators = mutableListOf<String?>()
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            if (req.method == HttpMethod.Get) respond("""{"task_key":"refresh_metadata","triggers":[]}""", headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"revision-1\"")))
            else {
                validators += req.headers[HttpHeaders.IfMatch]
                respond(V2Fixtures.problem(412, "precondition_failed", "Schedule changed"), HttpStatusCode.PreconditionFailed, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val snapshot = readSchedule(client, "refresh_metadata")
        assertEquals(UpdateTriggersResult.Conflict, saveSchedule(client, snapshot, listOf(TaskTrigger("startup"))))
        assertEquals(UpdateTriggersResult.Conflict, saveSchedule(client, snapshot, emptyList()))
        assertEquals(listOf("\"revision-1\"", "\"revision-1\""), validators)
        client.close()
    }
    @Test fun `schedule cannot open without an edit validator`() = runTest {
        val client = ApiClient("https://server.test", "token", MockEngine {
            respond("""{"task_key":"refresh_metadata","triggers":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { readSchedule(client, "refresh_metadata"); fail("Expected missing validator") } catch (_: IllegalArgumentException) { }
        client.close()
    }
}
