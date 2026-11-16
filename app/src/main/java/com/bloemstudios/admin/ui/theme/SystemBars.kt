package com.bloemstudios.admin.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Keeps the window behind the app and the status and navigation bar icons in step with the scheme in use, which
 * can differ from the system's when App settings picks Light or Dark.
 */
@Composable
fun SystemBarsFollowTheme() {
    val view = LocalView.current
    val canvas = VeilColors.Canvas
    val dark = LocalVeilPalette.current.isDark
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(canvas.toArgb()))
            window.statusBarColor = canvas.toArgb()
            window.navigationBarColor = canvas.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}

/**
 * For a full-screen dialog (call it inside the `Dialog` content, and open the dialog with
 * `decorFitsSystemWindows = false`): the dialog draws behind the status and navigation bars, they are transparent,
 * and their icons follow the scheme in use. Without this the dialog's own window leaves the system bars to the
 * window beneath it, which shows as a grey strip: the dim of the dialog laid over the app's bar.
 */
@Composable
fun FullScreenDialogSystemBars() {
    val view = LocalView.current
    val dark = LocalVeilPalette.current.isDark
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}
