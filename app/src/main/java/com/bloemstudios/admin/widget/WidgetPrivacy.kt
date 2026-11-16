package com.bloemstudios.admin.widget

/** How much of a widget's data may be drawn. */
enum class Detail {
    /** Names, titles and rows, as the app shows them once unlocked. */
    Full,

    /** Counts and statuses only: no person, title, library or node name. */
    CountsOnly,
}

/** Where a widget is drawn. [Keyguard] is a lock-screen widget (Android tablets and Android 16 and later). */
enum class Surface { HomeScreen, Keyguard }

/**
 * The one place the widget privacy rules (docs/design.md, "Widgets") are decided:
 *  - the lock screen shows counts and statuses only, unless "Show details on lock screen" (the alerts setting, one per device,
 *    off by default) is on;
 *  - on an unlocked phone a home-screen widget shows full detail and its buttons work. The app's own lock does not reduce
 *    that: the phone's unlock is the gate, and the app lock only guards opening the app;
 *  - the same setting decides what the Quick Settings tiles and the ongoing progress notification show on a locked device.
 */
object WidgetPrivacy {
    fun detail(surface: Surface, showDetailsOnLockScreen: Boolean): Detail =
        if (surface == Surface.Keyguard && !showDetailsOnLockScreen) Detail.CountsOnly else Detail.Full

    /** Whether the progress notification may carry a library name or a server name while the device is locked. */
    fun detailsOnLockedDevice(showDetailsOnLockScreen: Boolean): Boolean = showDetailsOnLockScreen
}
