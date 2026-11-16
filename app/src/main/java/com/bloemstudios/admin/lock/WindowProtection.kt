package com.bloemstudios.admin.lock

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Keeps the app's content out of the app switcher (recents) while the app lock is on. Screenshots of the unlocked app stay
 * allowed.
 *
 * - **API 33 and up:** `Activity.setRecentsScreenshotEnabled(false)` for as long as the lock is on. It only affects the
 *   thumbnail, so screenshots stay possible.
 * - **API 26 to 32:** `FLAG_SECURE` on the window, while the lock is on and the app is locked or not in the foreground.
 *   `FLAG_SECURE` also blocks screenshots and screen recording, so it is not left on while the unlocked app is in use.
 *
 * Both are set while the lock is on whether or not the app is locked at that moment: the system takes the thumbnail as the
 * app leaves the screen, which is before the lock engages (it engages when the app comes back).
 */
internal class WindowProtection(private val activity: Activity) {
    fun apply(lockOn: Boolean, locked: Boolean, resumed: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!lockOn)
        } else if (secureFlag(lockOn, locked, resumed)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        /** Whether API 26 to 32 should have `FLAG_SECURE` set. */
        fun secureFlag(lockOn: Boolean, locked: Boolean, resumed: Boolean): Boolean =
            lockOn && (locked || !resumed)
    }
}
