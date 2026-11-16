package com.bloemstudios.admin.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bloemstudios.admin.BuildConfig
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertNotifier
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.ADMIN_PUSH_TOKEN
import com.bloemstudios.admin.network.api.ADMIN_WIDGET_WAKES_TOKEN
import com.bloemstudios.admin.network.api.setPushWidgets
import com.bloemstudios.admin.network.api.actOnRequest
import com.bloemstudios.admin.network.api.registerPushDevice
import com.bloemstudios.admin.network.api.setPushKinds
import com.bloemstudios.admin.network.api.RequestAction
import com.bloemstudios.admin.platform.bloem
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStoreHolder
import com.bloemstudios.admin.servers.readSavedServers
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The real server session: the app's own sign-ins, the same one store the screens and widgets use. */
class StorePushSession(private val context: Context) : PushServerSession {
    private var lease: ServerStoreHolder.Lease? = null

    private fun store() = (lease ?: ServerStoreHolder.acquire(context).also { lease = it }).store
    private fun client(server: AdminServer): ApiClient = store().client(server)

    override fun hasSession(server: AdminServer): Boolean =
        store().let { it.accessToken(server.id) != null || it.refreshToken(server.id) != null }

    override suspend fun pushCapable(server: AdminServer): Boolean? {
        val detector = client(server).bloem
        detector.capabilities()?.let { return ADMIN_PUSH_TOKEN in it.feature_tokens }
        // null with a final "not Bloem" answer is a plain Silo server; null otherwise is an answer we could not get.
        return if (detector.knownPlainSilo) false else null
    }

    override suspend fun register(server: AdminServer, request: PushRegisterRequest): PushRegistered = client(server).registerPushDevice(request)

    override suspend fun setKinds(server: AdminServer, deviceId: String, kinds: Map<String, Boolean>) {
        client(server).setPushKinds(deviceId, kinds)
    }

    override suspend fun supportsWidgetWakes(server: AdminServer): Boolean =
        client(server).bloem.capabilities()?.feature_tokens.orEmpty().contains(ADMIN_WIDGET_WAKES_TOKEN)

    override suspend fun setWidgets(server: AdminServer, deviceId: String, report: WidgetReport) {
        client(server).setPushWidgets(deviceId, report.placed, report.channels)
    }

    override fun close() {
        lease?.close()
        lease = null
    }
}

/** Unregistering needs the display token only, never a sign-in, so it takes no session. */
private object NoPushSession : PushServerSession {
    override fun hasSession(server: AdminServer) = false
    override suspend fun pushCapable(server: AdminServer): Boolean? = null
    override suspend fun register(server: AdminServer, request: PushRegisterRequest): PushRegistered = error("no session")
    override suspend fun setKinds(server: AdminServer, deviceId: String, kinds: Map<String, Boolean>) = error("no session")
    override fun close() = Unit
}

private class StorePushActions(private val context: Context) : PushRequestActions, AutoCloseable {
    private var lease: ServerStoreHolder.Lease? = null
    private fun store() = (lease ?: ServerStoreHolder.acquire(context).also { lease = it }).store

    override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) {
        val store = store()
        check(store.accessToken(server.id) != null || store.refreshToken(server.id) != null) { "not signed in" }
        store.client(server).actOnRequest(requestId, if (approve) RequestAction.APPROVE else RequestAction.DECLINE)
    }

    override fun close() {
        lease?.close()
        lease = null
    }
}

private class ResourceActionLabels(private val context: Context) : PushActionLabels {
    override fun done(approve: Boolean) = context.getString(if (approve) R.string.push_action_approved else R.string.push_action_declined)
    override fun alreadyDone() = context.getString(R.string.push_action_already_done)
    override fun failed(approve: Boolean) = context.getString(R.string.push_action_failed, context.getString(if (approve) R.string.push_action_approve_lower else R.string.push_action_decline_lower))
}

/** The one place the push pieces are put together, with seams for tests. */
object PushRuntime {
    @Volatile private var sharedPreferences: PushPreferences? = null

    /** One instance per process, so the settings screen sees what a background sync changed. */
    fun preferences(context: Context): PushPreferences = sharedPreferences ?: synchronized(this) {
        sharedPreferences ?: PushPreferences(
            context.applicationContext.getSharedPreferences(PushPreferences.FILE, Context.MODE_PRIVATE),
        ).also { sharedPreferences = it }
    }

    /** Test seam. */
    internal fun resetForTest(context: Context? = null) {
        sharedPreferences = null
        // Unregister, not just forget: a listener that stays registered would report a second time.
        val listener = widgetListener
        if (context != null && listener != null) {
            context.applicationContext.getSharedPreferences(com.bloemstudios.admin.alerts.SurfacePreferences.FILE, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
        }
        widgetListener = null
        onWidgetsChanged = { PushScheduler.widgetsChanged(it) }
    }

    /** What happens when a widget is added or removed. A seam so a test can see it without WorkManager. */
    internal var onWidgetsChanged: (Context) -> Unit = { PushScheduler.widgetsChanged(it) }

    // SharedPreferences keeps its listeners weakly, so the one that watches the widgets is held here.
    @Volatile private var widgetListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Reports this device's widgets to the servers that send widget wakes whenever one is added or removed. It watches the record of
     * placed widgets, so it needs nothing from the widget code. It does nothing on a device that has no server able to push.
     */
    fun watchWidgets(context: Context) {
        val app = context.applicationContext
        if (widgetListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val isWidget = key != null && (key.startsWith(WIDGET_KEY) || key.startsWith(WIDGET_KIND_KEY))
            if (isWidget && preferences(app).wantsPeriodicSync) onWidgetsChanged(app)
        }
        widgetListener = listener
        app.getSharedPreferences(com.bloemstudios.admin.alerts.SurfacePreferences.FILE, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    private const val WIDGET_KEY = "widget:"
    private const val WIDGET_KIND_KEY = "widgetkind:"

    fun registrations(context: Context) = PushRegistrationStore.open(context)

    /** True when this build was built with a Firebase config for this app. */
    val firebaseConfigured: Boolean get() = BuildConfig.FIREBASE_CONFIGURED

    /** The FCM registration token, or null when this build has no Firebase or Firebase has none to give. */
    suspend fun fcmToken(): String? {
        if (!firebaseConfigured) return null
        return try {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Brings every saved server's registration in step with this device. Runs off the main thread (the store opens
     * storage). Returns how many registrations were made or refreshed.
     */
    suspend fun sync(context: Context): Int {
        val app = context.applicationContext
        val session = StorePushSession(app)
        return try {
            PushRegistrar(
                servers = { readSavedServers(app) },
                session = session,
                registrations = registrations(app),
                preferences = preferences(app),
                content = PushContentClient(),
                fcmToken = ::fcmToken,
                firebaseConfigured = firebaseConfigured,
                notificationsAllowed = { AlertNotifier.canPost(app) },
                // The widgets the system lists, so one the app has no note of still makes this device eligible for wakes.
                widgets = PlacedWidgets { com.bloemstudios.admin.widget.WidgetPlacement.placed(app) },
            ).reconcile().also {
                // From now on the daily sync keeps this device current; a device with no server able to push never gets here.
                // The channels exist from the first such server on, so they can be tuned before the first notification.
                if (preferences(app).wantsPeriodicSync) {
                    PushChannels.ensureAll(app)
                    PushScheduler.schedulePeriodic(app)
                }
            }
        } finally {
            session.close()
        }
    }

    /** Switches one kind on this device and tells the server. */
    suspend fun setKind(context: Context, server: AdminServer, kind: String, enabled: Boolean) {
        val app = context.applicationContext
        val session = StorePushSession(app)
        try {
            PushRegistrar(
                servers = { readSavedServers(app) }, session = session, registrations = registrations(app),
                preferences = preferences(app), content = PushContentClient(), fcmToken = ::fcmToken,
                firebaseConfigured = firebaseConfigured, notificationsAllowed = { AlertNotifier.canPost(app) },
            ).setKind(server, kind, enabled)
        } finally {
            session.close()
        }
    }

    /** Stops pushes for a server about to be signed out of or removed. Bounded, and never throws. */
    suspend fun unregister(context: Context, server: AdminServer) {
        val app = context.applicationContext
        try {
            PushRegistrar(
                servers = { emptyList() }, session = NoPushSession, registrations = registrations(app),
                preferences = preferences(app), content = PushContentClient(), fcmToken = { null },
                firebaseConfigured = firebaseConfigured, notificationsAllowed = { true },
            ).unregister(server)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Local record is dropped by unregister's own finally; nothing more to do.
        }
    }

    internal fun notifier(context: Context) = PushNotifier(context.applicationContext, AlertsRuntime.alertPreferences(context))

    /**
     * A notification is about something on that server (a request, a server down, a finished scan): the widgets that show it are
     * read again in a moment. An ignored push says nothing about any server, and a silent wake has its own job
     * ([WidgetWakeWorker]) which goes through the same refresh, so it is not asked for twice.
     */
    internal fun refreshWidgetsAfter(context: Context, outcome: PushOutcome) {
        if (outcome == PushOutcome.Posted || outcome == PushOutcome.PostedGeneric || outcome == PushOutcome.Suppressed) {
            com.bloemstudios.admin.widget.WidgetWork.refreshSoon(context.applicationContext, com.bloemstudios.admin.widget.RefreshReason.Push)
        }
    }

    internal fun receiver(context: Context): PushReceiver {
        val app = context.applicationContext
        return PushReceiver({ readSavedServers(app) }, registrations(app), preferences(app), PushContentClient(), notifier(app), WorkManagerWidgetWaker(app))
    }
}

/** Schedules the registration sync. Nothing is scheduled in a build without Firebase. */
object PushScheduler {
    private const val UNIQUE_NOW = "push-sync-now"
    private const val UNIQUE_PERIODIC = "push-sync"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** One sync soon: after a sign-in, a new FCM token or an app start. */
    fun syncSoon(context: Context) {
        if (!PushRuntime.firebaseConfigured) return
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PushSyncWorker>().setConstraints(constraints).build(),
        )
    }

    /**
     * A widget was added or removed: tell each server that sends widget wakes what this device's widgets show now. It is the same
     * sync as after a sign-in, which sends a report only where it changed. Nothing is scheduled in a build without Firebase.
     */
    fun widgetsChanged(context: Context) = syncSoon(context)

    /** Daily, so a token Firebase rotated without telling the service, or a server that came back, is caught up. */
    fun schedulePeriodic(context: Context) {
        if (!PushRuntime.firebaseConfigured) return
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PushSyncWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build(),
        )
    }
}

class PushSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PushRuntime.sync(applicationContext)
        return Result.success()
    }
}

/** Receives FCM. The message carries a delivery id and nothing else; the text is read from the server. */
class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushScheduler.syncSoon(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // A high-priority message gives this service a short window. The content fetch is bounded well inside it
        // (PushContentClient.TIMEOUT_MILLIS) and a server that is slow or silent leaves the generic notification.
        val outcome = runBlocking { PushRuntime.receiver(applicationContext).receive(message.data) }
        PushRuntime.refreshWidgetsAfter(applicationContext, outcome)
    }
}

/** The Approve and Decline buttons. They run only on an unlocked device (the notification action requires it). */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approve = when (intent.action) {
            ACTION_APPROVE -> true
            ACTION_DECLINE -> false
            else -> return
        }
        val serverId = intent.getStringExtra(EXTRA_SERVER) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val actions = StorePushActions(app)
            try {
                val result = withTimeoutOrNull(ACTION_BUDGET_MILLIS) {
                    PushActionRunner({ readSavedServers(app) }, actions, PushRuntime.notifier(app), ResourceActionLabels(app))
                        .run(serverId, requestId, approve, tag)
                }
                if (result == null) {
                    PushRuntime.notifier(app).showActionResult(null, tag, requestId, ResourceActionLabels(app).failed(approve), failed = true)
                }
            } finally {
                actions.close()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.bloemstudios.admin.push.APPROVE"
        const val ACTION_DECLINE = "com.bloemstudios.admin.push.DECLINE"
        const val EXTRA_SERVER = "server"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_TAG = "tag"
        private const val ACTION_BUDGET_MILLIS = 8_000L
    }
}
