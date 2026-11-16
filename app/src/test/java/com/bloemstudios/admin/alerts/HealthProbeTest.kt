package com.bloemstudios.admin.alerts

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTest {
    private fun probe(timeoutMillis: Long = 10_000, handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): Pair<HttpHealthProbe, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> seen += request; handler(request) }
        return HttpHealthProbe(timeoutMillis) { engine } to seen
    }

    @Test fun anAnswerFromTheHealthRouteMeansReachable() = runBlocking {
        val (probe, seen) = probe { respond("""{"status":"ok"}""", HttpStatusCode.OK) }
        assertTrue(probe.isReachable("https://home.test"))
        assertEquals(1, seen.size)
        assertEquals(HttpMethod.Get, seen[0].method)
        assertEquals("https://home.test/api/v1/health", seen[0].url.toString())
    }

    @Test fun theRequestCarriesNoCredentialsAndNoCookies() = runBlocking {
        val (probe, seen) = probe { respond("{}", HttpStatusCode.OK) }
        probe.isReachable("https://home.test")
        val headers = seen.single().headers
        assertNull(headers[HttpHeaders.Authorization])
        assertNull(headers[HttpHeaders.Cookie])
        assertTrue(headers.names().none { it.contains("token", ignoreCase = true) || it.equals("X-Api-Key", ignoreCase = true) })
    }

    @Test fun aServerErrorMeansNotReachable() = runBlocking {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable, HttpStatusCode.GatewayTimeout)) {
            val (probe, _) = probe { respondError(status) }
            assertFalse(status.toString(), probe.isReachable("https://home.test"))
        }
    }

    @Test fun anAnswerThatIsNotAServerErrorMeansTheServerIsThere() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone, HttpStatusCode.TooManyRequests, HttpStatusCode.Unauthorized)) {
            val (probe, _) = probe { respondError(status) }
            assertTrue(status.toString(), probe.isReachable("https://home.test"))
        }
    }

    @Test fun aConnectionFailureMeansNotReachable() = runBlocking {
        val (probe, _) = probe { throw IOException("connection refused") }
        assertFalse(probe.isReachable("https://home.test"))
    }

    @Test fun aSilentServerIsGivenUpOnAtTheTimeout() = runBlocking {
        val (probe, _) = probe(timeoutMillis = 50) { delay(5_000); respond("{}", HttpStatusCode.OK) }
        assertFalse(probe.isReachable("https://home.test"))
    }

    @Test fun anAddressThatIsNotAValidOriginIsNotReachableAndSendsNothing() = runBlocking {
        val (probe, seen) = probe { respond("{}", HttpStatusCode.OK) }
        assertFalse(probe.isReachable("http://home.test"))
        assertFalse(probe.isReachable("not an address"))
        assertTrue(seen.isEmpty())
    }

    @Test fun cancellationIsNotTurnedIntoAnAnswer() {
        val (probe, _) = probe { throw CancellationException("stopped") }
        val result = runCatching { runBlocking { probe.isReachable("https://home.test") } }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
