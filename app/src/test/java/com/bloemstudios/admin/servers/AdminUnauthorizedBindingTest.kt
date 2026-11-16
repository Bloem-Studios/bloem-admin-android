package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AdminUnauthorizedBindingTest {
    @Test
    fun `late unauthorized callback from an older credential cannot expire the current session`() = runTest {
        val old = AccessTokenSnapshot("old", 1)
        val current = AccessTokenSnapshot("current", 2)
        val client = ApiClient(ServerOrigin.parse("https://example.test"), current, MockEngine { error("unused") })
        var expirations = 0
        val binding = AdminUnauthorizedBinding(
            client = client,
            currentCredential = { current },
            onCurrentUnauthorized = { expirations++ }
        )

        client.onUnauthorized?.invoke(old)

        assertEquals(0, expirations)
        binding.close()
    }

    @Test
    fun `unauthorized callback for the current credential expires the session`() = runTest {
        val current = AccessTokenSnapshot("current", 2)
        val client = ApiClient(ServerOrigin.parse("https://example.test"), current, MockEngine { error("unused") })
        var expirations = 0
        val binding = AdminUnauthorizedBinding(
            client = client,
            currentCredential = { current },
            onCurrentUnauthorized = { expirations++ }
        )

        client.onUnauthorized?.invoke(current)

        assertEquals(1, expirations)
        binding.close()
    }

    @Test
    fun `login required callback routes after recovery clears the rejected credential`() = runTest {
        val rejected = AccessTokenSnapshot("expired", 4)
        val clearedByRecovery = AccessTokenSnapshot(null, 5)
        val client = ApiClient(ServerOrigin.parse("https://example.test"), rejected, MockEngine { error("unused") })
        var expirations = 0
        val binding = AdminUnauthorizedBinding(
            client = client,
            currentCredential = { clearedByRecovery },
            onCurrentUnauthorized = { expirations++ }
        )

        client.onUnauthorized?.invoke(rejected)

        assertEquals(1, expirations)
        binding.close()
    }
    @Test fun `epoch callback accepts token rotation but ordinary HTTP still rejects old credential`() = runTest {
        val old = AccessTokenSnapshot("old",1)
        val client = ApiClient(ServerOrigin.parse("https://example.test"),old,MockEngine { error("unused") })
        val gate = AdminSessionViewModel()
        val binding = AdminUnauthorizedBinding(client,{ client.accessTokenSnapshotForTest() },{ gate.reportAdminUnauthorized("fixture") })
        val epoch = client.currentPrincipalEpoch
        val terminal = requireNotNull(client.onTerminalAuthorityLost)
        client.refreshAccessToken(AccessTokenSnapshot("rotated",2))
        client.onUnauthorized?.invoke(old)
        org.junit.Assert.assertNull(gate.sessionExpiredReason("fixture"))
        terminal(epoch)
        org.junit.Assert.assertNotNull(gate.sessionExpiredReason("fixture"))
        binding.close(); client.close()
    }
    @Test fun `captured epoch callback rejects a replacement principal without repository precheck`() = runTest {
        val client = ApiClient("https://example.test","old",MockEngine { error("unused") })
        val gate = AdminSessionViewModel()
        val binding = AdminUnauthorizedBinding(client,{ client.accessTokenSnapshotForTest() },{ gate.reportAdminUnauthorized("fixture") })
        val epoch = client.currentPrincipalEpoch
        val terminal = requireNotNull(client.onTerminalAuthorityLost)
        client.replaceAuthenticatedPrincipal("replacement")
        terminal(epoch)
        org.junit.Assert.assertNull(gate.sessionExpiredReason("fixture"))
        binding.close(); client.close()
    }
    @Test fun `disposed binding rejects captured terminal callback and preserves successor hooks`() = runTest {
        val client = ApiClient("https://example.test","fixture",MockEngine { error("unused") })
        var oldExpired = 0; var newExpired = 0
        val old = AdminUnauthorizedBinding(client,{ client.accessTokenSnapshotForTest() },{ oldExpired++ })
        val queued = requireNotNull(client.onTerminalAuthorityLost)
        val replacement = AdminUnauthorizedBinding(client,{ client.accessTokenSnapshotForTest() },{ newExpired++ })
        old.close(); queued(client.currentPrincipalEpoch)
        client.onTerminalAuthorityLost?.invoke(client.currentPrincipalEpoch)
        assertEquals(0,oldExpired); assertEquals(1,newExpired)
        replacement.close()
        org.junit.Assert.assertNull(client.onUnauthorized)
        org.junit.Assert.assertNull(client.onTerminalAuthorityLost)
        client.close()
    }

}
