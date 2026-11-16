package com.bloemstudios.admin.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.CountsStatus
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.alerts.ServerStatus
import com.bloemstudios.admin.widget.JobKind
import com.bloemstudios.admin.widget.JobProgressNotifier
import com.bloemstudios.admin.widget.JobStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two Quick Settings tiles and the ongoing progress notification, photographed on the real system UI. The tiles read the
 * stored status of the last check, so made-up records are stored here first; no server is contacted (the saved-server list is
 * empty, so the check the tiles ask for stops at once). The notification is posted by the app's own notifier.
 *
 * Needs a debug-signed run on an emulator (`cmd statusbar` is a shell command).
 */
@RunWith(AndroidJUnit4::class)
class WidgetSurfacesScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val ui = instrumentation.uiAutomation
    private val tileNowPlaying = "com.bloemstudios.admin/com.bloemstudios.admin.widget.NowPlayingTileService"
    private val tileRequests = "com.bloemstudios.admin/com.bloemstudios.admin.widget.RequestsTileService"

    private val outDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
            ?: File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    private fun shell(command: String): String =
        ui.executeShellCommand(command).let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().decodeToString() } }

    private fun shoot(name: String) {
        Thread.sleep(1_500)
        val bitmap = ui.takeScreenshot()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seedStatuses() {
        val now = System.currentTimeMillis()
        // Two servers' last answers: 4 streams playing and 7 requests waiting in all.
        val store = AlertsRuntime.statusStore(context)
        store.put("sample-a", ServerRecord(ServerStatus(true, now, CountsStatus.Known, streams = 3, requests = 5)))
        store.put("sample-b", ServerRecord(ServerStatus(true, now, CountsStatus.Known, streams = 1, requests = 2)))
    }

    @Before fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After fun clean() {
        shell("cmd statusbar collapse")
        shell("cmd uimode night auto")
        shell("cmd statusbar remove-tile $tileNowPlaying")
        shell("cmd statusbar remove-tile $tileRequests")
        AlertsRuntime.statusStore(context).retain(emptySet())
        AlertsRuntime.surfacePreferences(context).tileAdded = false
        AlertsRuntime.surfacePreferences(context).requestsTileAdded = false
        (context.getSystemService(android.app.NotificationManager::class.java)).cancelAll()
    }

    @Test fun quickSettingsTiles() {
        shell("cmd statusbar add-tile $tileNowPlaying")
        shell("cmd statusbar add-tile $tileRequests")
        // Adding a tile asks for a check, which forgets records of servers that are not saved; seed after it has run.
        Thread.sleep(6_000)
        seedStatuses()
        listOf(com.bloemstudios.admin.widget.NowPlayingTileService::class.java, com.bloemstudios.admin.widget.RequestsTileService::class.java).forEach {
            android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, it))
        }
        for (night in listOf(true, false)) {
            shell("cmd uimode night ${if (night) "yes" else "no"}")
            shell("cmd statusbar collapse")
            Thread.sleep(800)
            shell("cmd statusbar expand-settings")
            Thread.sleep(800)
            // A second expand opens the full tile grid on a phone.
            shell("cmd statusbar expand-settings")
            shoot("tiles-quick-settings-${if (night) "dark" else "light"}")
        }
    }

    @Test fun progressNotification() {
        // Android 13 and later asks before the first notification; this is a test device, so allow it.
        shell("pm grant com.bloemstudios.admin android.permission.POST_NOTIFICATIONS")
        val notifier = JobProgressNotifier(context)
        notifier.show(JobKind.Backup, "sample-a", "Home", null, null, JobStatus.Running(63))
        notifier.show(JobKind.Scan, "sample-a", "Home", "lib-1", "Films", JobStatus.Running(null))
        for (night in listOf(true, false)) {
            shell("cmd uimode night ${if (night) "yes" else "no"}")
            shell("cmd statusbar collapse")
            Thread.sleep(800)
            shell("cmd statusbar expand-notifications")
            shoot("progress-notification-${if (night) "dark" else "light"}")
        }
    }
}
