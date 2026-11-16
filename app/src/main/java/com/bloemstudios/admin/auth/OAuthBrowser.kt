package com.bloemstudios.admin.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object OAuthBrowser {
    /** Opens [url] in a Custom Tab. Returns false when no browser can handle it. */
    fun open(context: Context, url: String): Boolean {
        val tab = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setShowTitle(true)
            .build()
        if (needsNewTask(context)) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, Uri.parse(url))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** True unless [context] is, or wraps, an Activity (Compose and themes hand out wrappers). */
    internal fun needsNewTask(context: Context): Boolean {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return false
            current = current.baseContext
        }
        return current !is Activity
    }
}
