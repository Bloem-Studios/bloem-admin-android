package com.bloemstudios.admin

import android.app.Application
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.push.PushRuntime
import com.bloemstudios.admin.push.PushScheduler
import com.bloemstudios.admin.realtime.AndroidLiveEnvironment
import com.bloemstudios.admin.realtime.LiveEnvironments
import com.bloemstudios.admin.widget.WidgetSync
import com.bloemstudios.admin.widget.WidgetWork

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The events socket opens only while the app is in front, and reconnects when the network changes.
        LiveEnvironments.current = AndroidLiveEnvironment(this)
        // Keeps the periodic check in step with the device's alert, widget and tile choices after an app update or a
        // reboot. A device that uses none of them never even opens the scheduler.
        ServerCheckScheduler.reconcile(this, touchWhenUnwanted = false)
        // The same for the widgets' own 15-minute read, from the widgets the system says exist (not a note the app kept).
        WidgetWork.reconcile(this, touchWhenUnwanted = false)
        // What the app saves reaches the widgets, and a started scan or backup gets its progress notification.
        WidgetSync.install(this)
        // Keeps each Bloem server's push registration current (a rotated FCM token, a server that came back). Only a device
        // that already has a server able to push is scheduled, so one that uses none of it never opens WorkManager.
        if (PushRuntime.preferences(this).wantsPeriodicSync) PushScheduler.schedulePeriodic(this)
        // A widget added or removed changes what a server's wakes are for, so tell the servers that send them.
        PushRuntime.watchWidgets(this)
    }
}
