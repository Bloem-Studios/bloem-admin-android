package com.bloemstudios.admin.servers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val SERVER_A = "server-a"
private const val SERVER_B = "server-b"

class AdminSessionViewModelTest {
    @Test
    fun `admin session cannot create or expose an impersonation principal`() {
        val publicMethods = AdminSessionViewModel::class.java.methods.map { it.name }

        assertFalse(publicMethods.any { it.contains("impersonat", ignoreCase = true) })
    }

    @Test
    fun `admin unauthorized expires only that server session`() {
        val viewModel = AdminSessionViewModel()

        viewModel.reportAdminUnauthorized(SERVER_A)

        assertNotNull(viewModel.sessionExpiredReason(SERVER_A))
        assertNull(viewModel.sessionExpiredReason(SERVER_B))
    }

    @Test
    fun `clearing session expired leaves another server expired`() {
        val viewModel = AdminSessionViewModel()
        viewModel.reportAdminUnauthorized(SERVER_A)
        viewModel.reportAdminUnauthorized(SERVER_B)

        viewModel.clearSessionExpired(SERVER_A)

        assertNull(viewModel.sessionExpiredReason(SERVER_A))
        assertNotNull(viewModel.sessionExpiredReason(SERVER_B))
    }
}
