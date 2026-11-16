package com.bloemstudios.admin.alerts

/** What to do when the admin flips the master alerts switch on. */
enum class EnableAlertsStep {
    /** Nothing to ask: switch alerts on. */
    TurnOn,
    /** Android 13 and later: ask for the notification permission now, in context, never at launch. */
    AskPermission,
    /** The permission was refused: keep alerts off and say where to change it. */
    StayOff,
}

/** Android 13 (API 33) added the notification permission; older versions need no question. */
internal const val NOTIFICATION_PERMISSION_API = 33

fun stepToEnableAlerts(sdkInt: Int, permissionGranted: Boolean): EnableAlertsStep =
    if (sdkInt >= NOTIFICATION_PERMISSION_API && !permissionGranted) EnableAlertsStep.AskPermission else EnableAlertsStep.TurnOn

fun stepAfterPermissionAnswer(granted: Boolean): EnableAlertsStep =
    if (granted) EnableAlertsStep.TurnOn else EnableAlertsStep.StayOff

enum class AlertDeliveryStatus {
    Off,
    On,
    /** Alerts are switched on in the app but notifications are blocked for it in system settings. */
    BlockedBySystem,
}

fun alertDeliveryStatus(enabled: Boolean, systemAllows: Boolean): AlertDeliveryStatus = when {
    !enabled -> AlertDeliveryStatus.Off
    systemAllows -> AlertDeliveryStatus.On
    else -> AlertDeliveryStatus.BlockedBySystem
}
