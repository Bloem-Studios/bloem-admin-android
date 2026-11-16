package com.bloemstudios.admin.servers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddServerPersistenceTest {
    private val connected = AddServerResult.Connected(
        server = AdminServer("server-id", "https://example.test", "Example"),
        accessToken = "access",
        refreshToken = "refresh"
    )

    @Test
    fun `storage failure becomes a resource-mappable rejection instead of escaping the UI flow`() = runTest {
        val result = persistConnectedServer(connected) { _, _, _ ->
            error("DataStore write failed")
        }

        assertEquals(AddServerFailure.STORAGE_FAILED, (result as AddServerResult.Rejected).reason)
    }

    @Test
    fun `storage cancellation propagates from the UI flow`() = runTest {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                persistConnectedServer(connected) { _, _, _ -> throw CancellationException("cancelled") }
            }
        }
    }
}
