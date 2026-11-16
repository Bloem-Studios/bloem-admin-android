package com.bloemstudios.admin.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.widget.NowPlayingWidgetReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a real system image: a widget the launcher bound without ever showing the app's configuration screen (the case that left
 * no note, and so no job) gets the widgets' 15-minute job, and the job is really in the system's JobScheduler. Run it on a fresh
 * install. The test acts as a widget host, which needs the BIND_APPWIDGET permission; it borrows the shell's for the bind only.
 *
 *   ANDROID_SERIAL=<your own emulator> ./gradlew connectedMinifiedTestAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetJobInstalledTest
 */
@RunWith(AndroidJUnit4::class)
class WidgetJobInstalledTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** The app's WorkManager jobs as the system lists them: the `JobStatus` line of each, from `dumpsys jobscheduler`. */
    private fun jobLines(): List<String> = shell("dumpsys jobscheduler").lines()
        .filter { it.trimStart().startsWith("JobStatus{") && "com.bloemstudios.admin/androidx.work.impl.background.systemjob.SystemJobService" in it }

    /** The widgets' 15-minute job: it waits for a network (`NET`) and is due in 9 to 15 minutes (a periodic job runs in the last part of its period). */
    private fun periodicJob(lines: List<String>): String? = lines.firstOrNull { line ->
        " NET " in line && Regex("""TIME=\+(\d+)m""").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 9..15 } == true
    }

    @Test fun aWidgetPlacedWithoutItsConfigurationScreenStillGetsTheFifteenMinuteJob() {
        val outDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, NowPlayingWidgetReceiver::class.java)
        assertEquals("a fresh install has no widget placed", 0, manager.getAppWidgetIds(component).size)
        val before = jobLines()
        outDir?.let { File(it, "jobscheduler-before-widget.txt").writeText(if (before.isEmpty()) "(no WorkManager job for com.bloemstudios.admin)" else before.joinToString("\n")) }
        assertTrue("and no 15-minute job: $before", periodicJob(before) == null)

        val host = AppWidgetHost(context, 4242)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        try {
            widgetId = host.allocateAppWidgetId()
            assertTrue("the system accepted the widget", manager.bindAppWidgetIdIfAllowed(widgetId, component))
            host.startListening()
            // The system tells the provider its first widget exists (and later, that it should update); the receiver schedules the job.
            val deadline = System.currentTimeMillis() + 30_000
            var jobs = jobLines()
            while (periodicJob(jobs) == null && System.currentTimeMillis() < deadline) { Thread.sleep(500); jobs = jobLines() }
            outDir?.let {
                File(it, "jobscheduler-after-widget-placed.txt").writeText(jobs.joinToString("\n"))
                // The full block of each of the app's jobs, to read what each one is.
                val all = shell("dumpsys jobscheduler").lines()
                val blocks = all.indices.filter { i -> all[i].trimStart().startsWith("JOB androidx.work.systemjobscheduler:") && "bloemstudios" in all[i] }
                    .joinToString("\n----\n") { i -> all.subList(i, minOf(i + 45, all.size)).joinToString("\n") }
                File(it, "debug-job-blocks.txt").writeText(blocks)
            }
            assertTrue("JobScheduler lists the 15-minute network job once a widget is placed:\n${jobs.joinToString("\n")}", periodicJob(jobs) != null)
        } finally {
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
            host.stopListening()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        // The last widget went: the job goes with it.
        val deadline = System.currentTimeMillis() + 30_000
        var left = jobLines()
        while (periodicJob(left) != null && System.currentTimeMillis() < deadline) { Thread.sleep(500); left = jobLines() }
        outDir?.let { File(it, "jobscheduler-after-widget-removed.txt").writeText(if (left.isEmpty()) "(no WorkManager job for com.bloemstudios.admin)" else left.joinToString("\n")) }
        assertTrue("with the last widget removed the 15-minute job is cancelled:\n${left.joinToString("\n")}", periodicJob(left) == null)
    }
}
