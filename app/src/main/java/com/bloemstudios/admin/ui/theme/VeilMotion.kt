package com.bloemstudios.admin.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class VeilMotionTokens(
    /** Feedback: a press, a toggle, a small change in place. The spec allows 150 to 200. */
    val fastMillis: Int = 150,
    /** Menus, fades between pages, the content swap. */
    val normalMillis: Int = 200,
    /** Entering: a chart drawing in, a sheet or panel arriving. */
    val sheetMillis: Int = 250,
    /** The one easing curve of the app (cubic-bezier(0.22, 1, 0.36, 1)). Nothing bounces or overshoots. */
    val ease: CubicBezierEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
)

val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * When set, [VeilAdminTheme] uses this motion mode instead of reading the device's animation scales.
 * Tests set it so normal and reduced motion can each be checked on any device, including emulators
 * that run with animations disabled (which the system reports as reduced motion).
 */
val LocalReducedMotionOverride: ProvidableCompositionLocal<Boolean?> = staticCompositionLocalOf { null }

@androidx.compose.runtime.Composable
internal fun systemReducedMotion(): Boolean {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    fun read() = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    val reduced = androidx.compose.runtime.remember(resolver) { androidx.compose.runtime.mutableStateOf(read()) }
    androidx.compose.runtime.DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced.value = read() }
        }
        resolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced.value
}
