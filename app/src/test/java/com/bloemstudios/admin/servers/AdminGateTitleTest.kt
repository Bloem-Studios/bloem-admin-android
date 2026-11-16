package com.bloemstudios.admin.servers

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminGateTitleTest {
    @Test
    fun everyScreenBeforeTheShellHasItsOwnTitle() {
        assertEquals(R.string.shell_title_checking_access, adminGateTitleRes(AdminGateState.Checking))
        assertEquals(R.string.shell_title_sign_in, adminGateTitleRes(AdminGateState.NeedsLogin()))
        assertEquals(R.string.shell_title_sign_in, adminGateTitleRes(AdminGateState.NeedsLogin("Expired")))
        assertEquals(R.string.shell_title_organizations, adminGateTitleRes(AdminGateState.OrganizationOnly(emptyList())))
        assertEquals(R.string.shell_title_no_access, adminGateTitleRes(AdminGateState.Unauthorized))
        val failed = adminGateTitleRes(AdminGateState.Failed(ErrorText.Res(R.string.error_admin_gate_unreachable)))
        assertEquals(R.string.shell_title_unreachable, failed)
        val titles = listOf(
            AdminGateState.Checking, AdminGateState.NeedsLogin(), AdminGateState.OrganizationOnly(emptyList()),
            AdminGateState.Unauthorized,
        ).map(::adminGateTitleRes)
        assertTrue("each state reads differently", titles.toSet().size == titles.size)
    }
}
