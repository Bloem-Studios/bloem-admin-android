package com.bloemstudios.admin.auth

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnauthorizedRecoveryTest {
    @Test
    fun accessTokenSnapshotToStringRedactsToken() {
        val rendered = AccessTokenSnapshot("top-secret-access-token", 7L).toString()

        assertEquals("AccessTokenSnapshot(token=<redacted>, generation=7)", rendered)
        assertFalse(rendered.contains("top-secret-access-token"))
    }

    @Test
    fun recoveryResultsDoNotRenderTokenOrExceptionText() {
        val retry = RecoveryResult.Retry(AccessTokenSnapshot("top-secret-access-token", 7L))
        val unavailable = RecoveryResult.Unavailable(IOException("private transport detail"))

        assertFalse(retry.toString().contains("top-secret-access-token"))
        assertFalse(unavailable.toString().contains("private transport detail"))
        assertTrue(unavailable.toString().contains("<redacted>"))
    }
}
