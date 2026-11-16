package com.bloemstudios.admin.invitations

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle

internal const val SENSITIVE_CLIP_EXTRA = "android.content.extra.IS_SENSITIVE"

internal class SensitiveInvitationClipboard(private val clipboard: ClipboardManager) {
    fun copy(secret: String) {
        val clip = ClipData.newPlainText("Invitation link", secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE_CLIP_EXTRA, true)
        }
        clipboard.setPrimaryClip(clip)
    }

    fun clearIfOwned(secret: String) {
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
        if (current != secret) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
