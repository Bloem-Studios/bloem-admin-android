package com.bloemstudios.admin.widget

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertNotifier
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.backups.BackupList
import com.bloemstudios.admin.libraries.ScanRun
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.listAdminLibraries
import com.bloemstudios.admin.network.api.listBackups
import com.bloemstudios.admin.network.api.listScanRuns
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStoreHolder
import com.bloemstudios.admin.servers.readSavedServers
import com.bloemstudios.admin.ui.theme.VeilPalette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** What an ongoing notification follows: a library scan, or a backup. */
enum class JobKind(val storage: String, val page: String) {
    Scan("scan", "libraries"),
    Backup("backup", "backups"),
}

/** Where a followed job stands. [percent] is null when the server does not measure it (a scan). */
sealed interface JobStatus {
    /** Asked for, not listed by the server yet. */
    data object Starting : JobStatus
    data class Running(val percent: Int?) : JobStatus
    data object Succeeded : JobStatus
    data object Failed : JobStatus

    /** Never showed up in the server's lists: nothing to follow. */
    data object Gone : JobStatus
}

/** The one place that decides a job's status from what the server lists. Pure, so every case has a unit test. */
object JobProgressLogic {
    /** Polls with nothing listed before a job that never appeared is dropped. At 10 seconds each, about a minute. */
    const val MAX_UNSEEN_POLLS = 6

    /** A job is followed for at most this long: a stuck one must not keep a notification forever. */
    val MAX_AGE_MILLIS: Long = TimeUnit.HOURS.toMillis(3)

    fun backup(list: BackupList, seenActive: Boolean, unseenPolls: Int): JobStatus {
        val active = list.active?.takeIf { it.operation == "backup" }
        if (active != null) {
            val percent = active.progress.takeIf { it.isFinite() }?.toInt()?.coerceIn(0, 100)
            return JobStatus.Running(percent)
        }
        if (seenActive) {
            val run = list.last_run
            return if (run != null && (run.status == "failed" || run.status == "interrupted")) JobStatus.Failed else JobStatus.Succeeded
        }
        // Another operation (a restore, a delete) holds the lock: this backup is still waiting.
        if (list.busy) return JobStatus.Starting
        return if (unseenPolls >= MAX_UNSEEN_POLLS) JobStatus.Gone else JobStatus.Starting
    }

    fun scan(libraryId: String, runs: List<ScanRun>, seenActive: Boolean, unseenPolls: Int): JobStatus {
        val mine = runs.filter { it.library_id == libraryId }
        if (mine.any { it.status == "running" }) return JobStatus.Running(null)
        if (mine.any { it.status == "accepted" }) return JobStatus.Starting
        if (seenActive) {
            val latest = mine.maxByOrNull { it.completed_at ?: it.started_at ?: it.requested_at ?: "" }
            return if (latest?.status == "failed") JobStatus.Failed else JobStatus.Succeeded
        }
        return if (unseenPolls >= MAX_UNSEEN_POLLS) JobStatus.Gone else JobStatus.Starting
    }

    fun isDone(status: JobStatus) = status is JobStatus.Succeeded || status is JobStatus.Failed || status is JobStatus.Gone
}

/** Words for the ongoing notification, split from the posting so the lock-screen rule is testable. */
data class JobNotificationText(val title: String, val text: String, val publicTitle: String, val publicText: String, val percent: Int?, val ongoing: Boolean)

object JobNotificationWords {
    /**
     * [libraryName] is a detail, so it only goes into the title shown once unlocked; the public version (what a locked screen
     * shows unless the admin chose details) names neither the library nor the server.
     */
    fun build(context: Context, kind: JobKind, status: JobStatus, serverName: String, libraryName: String?): JobNotificationText {
        val subject = if (kind == JobKind.Scan) libraryName ?: context.getString(R.string.job_a_library) else serverName
        val title = when (status) {
            JobStatus.Succeeded -> context.getString(if (kind == JobKind.Scan) R.string.job_scan_done else R.string.job_backup_done, subject)
            JobStatus.Failed -> context.getString(if (kind == JobKind.Scan) R.string.job_scan_failed else R.string.job_backup_failed, subject)
            else -> context.getString(if (kind == JobKind.Scan) R.string.job_scan_running else R.string.job_backup_running, subject)
        }
        val publicTitle = when (status) {
            JobStatus.Succeeded -> context.getString(if (kind == JobKind.Scan) R.string.job_public_scan_done else R.string.job_public_backup_done)
            JobStatus.Failed -> context.getString(if (kind == JobKind.Scan) R.string.job_public_scan_failed else R.string.job_public_backup_failed)
            else -> context.getString(if (kind == JobKind.Scan) R.string.job_public_scan_running else R.string.job_public_backup_running)
        }
        val percent = (status as? JobStatus.Running)?.percent
        val text = when {
            status is JobStatus.Succeeded || status is JobStatus.Failed -> serverName
            percent != null -> context.getString(R.string.job_progress_on, percent, serverName)
            else -> serverName
        }
        return JobNotificationText(title, text, publicTitle, context.getString(R.string.job_tap_to_open), percent, ongoing = !JobProgressLogic.isDone(status))
    }
}

/** Posts the ongoing progress notification, and the quiet note that replaces it when the job ends. */
class JobProgressNotifier(private val context: Context) {
    @SuppressLint("MissingPermission") // checked by canPost()
    fun show(kind: JobKind, serverId: String, serverName: String, libraryId: String?, libraryName: String?, status: JobStatus) {
        val tag = tagFor(kind, serverId, libraryId)
        if (status is JobStatus.Gone) { NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID); return }
        if (!AlertNotifier.canPost(context)) return
        ensureChannel(context)
        val words = JobNotificationWords.build(context, kind, status, serverName, libraryName)
        val details = WidgetPrivacy.detailsOnLockedDevice(AlertsRuntime.alertPreferences(context).showDetailsOnLockScreen.value)
        fun base(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bloem)
            .setColor(VeilPalette.Dark.accent.toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open(kind, serverId))
        val notification = base(words.title, words.text)
            .setOngoing(words.ongoing)
            .setAutoCancel(!words.ongoing)
            .apply {
                if (words.ongoing) setProgress(100, words.percent ?: 0, words.percent == null)
                else setTimeoutAfter(FINISHED_LIFETIME_MILLIS)
            }
            .setVisibility(if (details) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                if (details) null
                else base(words.publicTitle, words.publicText).setOngoing(words.ongoing).apply { if (words.ongoing) setProgress(100, words.percent ?: 0, words.percent == null) }.build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
    }

    private fun open(kind: JobKind, serverId: String): PendingIntent = PendingIntent.getActivity(
        context, (kind.storage + serverId).hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(DeepLink.build(serverId, kind.page)), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "job_progress"
        private const val NOTIFICATION_ID = 2
        private const val FINISHED_LIFETIME_MILLIS = 30L * 60 * 1000

        fun tagFor(kind: JobKind, serverId: String, libraryId: String?) = "job:${kind.storage}:$serverId:${libraryId.orEmpty()}"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.job_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.job_channel_description)
                    // lockscreenVisibility is left unset on purpose: each notification chooses (see show).
                    setShowBadge(false)
                },
            )
        }
    }
}

/** Starts following a scan or a backup the app has just started. */
object JobProgressTracker {
    const val POLL_SECONDS = 10L

    internal const val KEY_KIND = "kind"
    internal const val KEY_SERVER = "server"
    internal const val KEY_LIBRARY = "library"
    internal const val KEY_STARTED = "startedAt"
    internal const val KEY_SEEN = "seenActive"
    internal const val KEY_UNSEEN = "unseenPolls"

    fun start(context: Context, kind: JobKind, serverId: String, libraryId: String?, nowMillis: Long = System.currentTimeMillis()) {
        enqueue(context, kind, serverId, libraryId, nowMillis, seen = false, unseen = 0, delaySeconds = 0)
    }

    internal fun enqueue(context: Context, kind: JobKind, serverId: String, libraryId: String?, startedAt: Long, seen: Boolean, unseen: Int, delaySeconds: Long) {
        val data = Data.Builder()
            .putString(KEY_KIND, kind.storage).putString(KEY_SERVER, serverId).putString(KEY_LIBRARY, libraryId)
            .putLong(KEY_STARTED, startedAt).putBoolean(KEY_SEEN, seen).putInt(KEY_UNSEEN, unseen)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            JobProgressNotifier.tagFor(kind, serverId, libraryId),
            // A newer poll replaces one that is waiting; two scans of the same library are one job to follow.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<JobProgressWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInputData(data)
                .build(),
        )
    }
}

/** One poll of a followed job: reads the server, updates the notification, and plans the next poll while it runs. */
class JobProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(JobProgressTracker.KEY_KIND)?.let { s -> JobKind.entries.firstOrNull { it.storage == s } } ?: return Result.success()
        val serverId = inputData.getString(JobProgressTracker.KEY_SERVER) ?: return Result.success()
        val libraryId = inputData.getString(JobProgressTracker.KEY_LIBRARY)
        val startedAt = inputData.getLong(JobProgressTracker.KEY_STARTED, System.currentTimeMillis())
        val seen = inputData.getBoolean(JobProgressTracker.KEY_SEEN, false)
        val unseen = inputData.getInt(JobProgressTracker.KEY_UNSEEN, 0)
        val context = applicationContext
        val notifier = JobProgressNotifier(context)
        val server = readSavedServers(context).firstOrNull { it.id == serverId }
        if (server == null) { notifier.show(kind, serverId, "", libraryId, null, JobStatus.Gone); return Result.success() }

        var libraryName: String? = null
        val lease = ServerStoreHolder.acquire(context)
        val status = try {
            val store = lease.store
            if (store.accessToken(serverId) == null && store.refreshToken(serverId) == null) JobStatus.Gone
            else {
                val client = store.client(server)
                if (kind == JobKind.Scan && libraryId != null) {
                    libraryName = runCatching { client.listAdminLibraries().firstOrNull { it.id == libraryId }?.name }.getOrNull()
                }
                read(kind, client, libraryId, seen, unseen)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The server did not answer this time; try again, but not forever.
            null
        } finally {
            lease.close()
        }

        val tooOld = System.currentTimeMillis() - startedAt > JobProgressLogic.MAX_AGE_MILLIS
        val effective = if (tooOld) JobStatus.Gone else status
        if (effective != null) notifier.show(kind, serverId, server.displayName, libraryId, libraryName, effective)
        if (effective == null || !JobProgressLogic.isDone(effective)) {
            val sawIt = seen || effective is JobStatus.Running
            JobProgressTracker.enqueue(
                context, kind, serverId, libraryId, startedAt, sawIt,
                unseen = if (sawIt) 0 else unseen + 1, delaySeconds = JobProgressTracker.POLL_SECONDS,
            )
        }
        // The widgets show the same scan and backup state: read them again when a job ends.
        if (effective != null && JobProgressLogic.isDone(effective)) SurfaceRefresher.dataChanged(context)
        return Result.success()
    }

    private suspend fun read(kind: JobKind, client: ApiClient, libraryId: String?, seen: Boolean, unseen: Int): JobStatus = when (kind) {
        JobKind.Backup -> JobProgressLogic.backup(client.listBackups(), seen, unseen)
        JobKind.Scan -> JobProgressLogic.scan(libraryId.orEmpty(), client.listScanRuns(null, limit = 30), seen, unseen)
    }
}

/** A short-lived pointer to the saved server, resolved from an API client's address. */
internal fun serverForOrigin(servers: List<AdminServer>, origin: String): AdminServer? =
    servers.firstOrNull { runCatching { com.bloemstudios.admin.network.ServerOrigin.parse(it.baseUrl).issuer }.getOrNull() == origin }
