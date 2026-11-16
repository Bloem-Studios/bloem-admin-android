package com.bloemstudios.admin.invitations

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveInvitationClipboardTest {
    @Test
    fun `copy marks invitation secret sensitive and owned clear removes it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val secret = "https://example.test/invite/secret"
        val invitationClipboard = SensitiveInvitationClipboard(clipboard)

        invitationClipboard.copy(secret)

        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(SENSITIVE_CLIP_EXTRA))
        assertEquals(secret, clipboard.primaryClip!!.getItemAt(0).text.toString())

        invitationClipboard.clearIfOwned(secret)
        assertTrue(clipboard.primaryClip == null || clipboard.primaryClip!!.getItemAt(0).text.isNullOrEmpty())
    }
}
