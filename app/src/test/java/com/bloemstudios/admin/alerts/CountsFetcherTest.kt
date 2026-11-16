package com.bloemstudios.admin.alerts

import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CountsFetcherTest {
    private val server = AdminServer("a", "https://home.test", "Bloem Home")

    private fun fetch(
        hasSession: Boolean = true,
        read: suspend (AdminServer) -> Pair<Int, Int> = { 2 to 3 },
        calls: MutableList<String> = mutableListOf(),
    ): CountsResult = runBlocking {
        CountsFetcher(hasSession = { hasSession }, read = { calls += it.id; read(it) }).fetch(server)
    }

    @Test fun aSessionGivesTheNumbers() {
        assertEquals(CountsResult.Known(streams = 2, requests = 3), fetch())
    }

    @Test fun noStoredSessionMeansSignInAndNoNetworkCall() {
        val calls = mutableListOf<String>()
        assertEquals(CountsResult.SignInNeeded, fetch(hasSession = false, calls = calls))
        assertTrue(calls.isEmpty())
    }

    @Test fun aRefusedSessionMeansSignIn() {
        assertEquals(CountsResult.SignInNeeded, fetch(read = { throw ApiError(401, "expired") }))
    }

    @Test fun anAccountThatIsNotAnAdministratorIsUnavailableNotSignIn() {
        assertEquals(CountsResult.Unavailable, fetch(read = { throw ApiError(403, "no") }))
    }

    @Test fun otherFailuresAreUnavailable() {
        assertEquals(CountsResult.Unavailable, fetch(read = { throw ApiError(500, "boom") }))
        assertEquals(CountsResult.Unavailable, fetch(read = { throw IOException("reset") }))
        assertEquals(CountsResult.Unavailable, fetch(read = { throw IllegalStateException("bad json") }))
    }

    @Test fun cancellationIsNotSwallowed() {
        val result = runCatching { fetch(read = { throw CancellationException("stopped") }) }
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
