package com.bloemstudios.admin.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bloemstudios.admin.alerts.ServerCheckScheduler

/**
 * After the phone starts and after the app is updated (the system drops an app's scheduled jobs when it is replaced), makes the
 * widgets' schedule match the widgets that exist and reads them once. A phone with no widget is left alone.
 */
class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (!WidgetWork.reconcile(app, touchWhenUnwanted = false)) return
        ServerCheckScheduler.reconcile(app, touchWhenUnwanted = false)
        WidgetWork.refreshSoon(app, RefreshReason.Boot)
    }
}
