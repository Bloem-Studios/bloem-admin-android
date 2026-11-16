package com.bloemstudios.admin.push

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bloemstudios.admin.widget.PlacedWidget
import com.bloemstudios.admin.widget.WidgetKind

/**
 * Widget wakes. Widgets never open sockets. A Bloem server sends a silent push
 * when something a widget shows changed, and only to a device that reported a widget; this app then reads again through its
 * normal REST API. A wake shows nothing, plays nothing and starts no socket.
 */
object WidgetChannels {
    const val SESSIONS = "sessions"
    const val REQUESTS = "requests"
    const val LIBRARIES = "libraries"
    const val NODES = "nodes"

    /** The channels a server knows. A name outside this list is never sent and never trusted. */
    val all: Set<String> = linkedSetOf(SESSIONS, REQUESTS, LIBRARIES, NODES)

    /** The channel a widget kind shows, or null for one that shows nothing a server pushes about (server status is its own check). */
    fun forKind(kind: WidgetKind): String? = when (kind) {
        WidgetKind.NowPlaying -> SESSIONS
        WidgetKind.Requests -> REQUESTS
        WidgetKind.Libraries -> LIBRARIES
        WidgetKind.Nodes -> NODES
        WidgetKind.ServerStatus -> null
    }

    /** The widget kinds a changed channel affects. */
    fun kindsFor(channels: Set<String>): Set<WidgetKind> = WidgetKind.entries.filter { forKind(it) in channels }.toSet()
}

/** What this device tells one server about its widgets: whether any is placed and which channels they show. */
data class WidgetReport(val placed: Boolean, val channels: List<String>) {
    /** A stable text for "what did I last tell this server", so an unchanged report is not sent again. */
    val key: String get() = if (placed) channels.joinToString(",") else ""

    companion object {
        val None = WidgetReport(placed = false, channels = emptyList())

        /**
         * What [serverId]'s widgets show. A widget on that server counts, and so does a widget on All servers (target null),
         * which shows every server. A server-status widget shows nothing a server pushes about, so it does not make the
         * device eligible for wakes.
         */
        fun forServer(widgets: List<PlacedWidget>, serverId: String): WidgetReport {
            val channels = widgets
                .filter { it.target == null || it.target == serverId }
                .mapNotNull { WidgetChannels.forKind(it.kind) }
                .toSortedSet()
            return if (channels.isEmpty()) None else WidgetReport(placed = true, channels = channels.toList())
        }
    }
}

/**
 * A wake read from a pushed delivery id. The server puts the reason and the channels that changed in the id, because the relay
 * carries no other field: `widgets-{channels}-{unix seconds}`, for example `widgets-nodes-requests-1763254800`.
 */
object PushWake {
    private const val REASON = "widgets"
    private val UNIX = Regex("^\\d{1,12}$")

    /** The channels a wake names, or null when [deliveryId] is not a well-formed widget wake. */
    fun channelsOf(deliveryId: String): Set<String>? {
        val parts = deliveryId.split('-')
        if (parts.size < 3 || parts.first() != REASON || !UNIX.matches(parts.last())) return null
        val channels = parts.subList(1, parts.size - 1)
        if (channels.isEmpty() || channels.any { it !in WidgetChannels.all }) return null
        return channels.toSet()
    }
}

/** What a received wake does: schedule a refresh of the affected widgets, quietly. [WorkManagerWidgetWaker] is the real one. */
fun interface WidgetWaker {
    fun wake(serverId: String, channels: Set<String>)
}

/** Reads the widgets that are placed. A seam: the widget code owns how that is known. */
fun interface PlacedWidgets {
    fun placed(): List<PlacedWidget>
}

/**
 * The real waker: one expedited, one-time WorkManager job. On Android 12 and later it is expedited; before that WorkManager would
 * make an expedited job a foreground service with a notification, and a wake must never show anything, so it is plain work that
 * still has no delay. A newer wake replaces one that has not started: the newest state wins, and the job reads everything the
 * widgets show.
 */
class WorkManagerWidgetWaker(private val context: Context) : WidgetWaker {
    override fun wake(serverId: String, channels: Set<String>) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request(serverId, channels))
    }

    companion object {
        /** The job for one wake. [sdk] is a parameter so both sides of the Android 12 line can be tested. */
        internal fun request(serverId: String, channels: Set<String>, sdk: Int = Build.VERSION.SDK_INT): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WidgetWakeWorker>()
                .setInputData(Data.Builder().putString(KEY_SERVER, serverId).putStringArray(KEY_CHANNELS, channels.sorted().toTypedArray()).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply { if (sdk >= EXPEDITED_API) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()

        const val UNIQUE = "widget-wake"
        const val KEY_SERVER = "server"
        const val KEY_CHANNELS = "channels"
        internal const val EXPEDITED_API = 31
    }
}

/** Where the woken job's refresh goes. The widget code owns this entry point; see [WidgetRefreshGateway]. */
fun interface WidgetRefreshStep {
    suspend fun refresh(context: Context, kinds: Set<WidgetKind>)
}

class WidgetWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channels = inputData.getStringArray(WorkManagerWidgetWaker.KEY_CHANNELS).orEmpty().filter { it in WidgetChannels.all }.toSet()
        WidgetRefreshGateway.step.refresh(applicationContext, WidgetChannels.kindsFor(channels))
        return Result.success()
    }
}

/**
 * The one place a wake meets the widgets' refresh. It goes through [com.bloemstudios.admin.widget.runWidgetRefresh], the same entry
 * point the 15-minute job, the Refresh button, a push, the app going to the background and the live socket use: one read of each
 * server for what the placed widgets show, a redraw, and the follow-up while something plays. Nothing else in the wake path touches
 * widget internals.
 */
object WidgetRefreshGateway {
    /** Test seam, and the place to point at a new widget refresh entry point. */
    @Volatile
    var step: WidgetRefreshStep = WidgetRefreshStep { context, kinds ->
        // The kinds the woken channels show; a wake that names none reads everything the widgets show.
        com.bloemstudios.admin.widget.runWidgetRefresh(context, kinds = kinds.ifEmpty { null })
    }
}
