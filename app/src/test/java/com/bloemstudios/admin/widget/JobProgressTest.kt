package com.bloemstudios.admin.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.backups.BackupActive
import com.bloemstudios.admin.backups.BackupLastRun
import com.bloemstudios.admin.backups.BackupList
import com.bloemstudios.admin.libraries.ScanRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ongoing notification for a scan or a backup started from the app. */
@RunWith(RobolectricTestRunner::class)
class JobProgressTest {
    private fun active(progress: Double, operation: String = "backup") = BackupActive(operation = operation, progress = progress)
    private fun run(status: String) = BackupLastRun(id = "b1", kind = "manual", status = status, started_at = "2026-11-15T10:00:00Z")

    @Test fun aRunningBackupReportsItsPercentage() {
        assertEquals(JobStatus.Running(42), JobProgressLogic.backup(BackupList(active = active(42.7)), seenActive = false, unseenPolls = 0))
    }

    @Test fun aPercentageOutsideTheRangeIsClamped() {
        assertEquals(JobStatus.Running(100), JobProgressLogic.backup(BackupList(active = active(180.0)), false, 0))
        assertEquals(JobStatus.Running(0), JobProgressLogic.backup(BackupList(active = active(-3.0)), false, 0))
        assertEquals(JobStatus.Running(null), JobProgressLogic.backup(BackupList(active = active(Double.NaN)), false, 0))
    }

    @Test fun aBackupThatEndedAfterRunningSucceedsOrFailsByItsLastRun() {
        assertEquals(JobStatus.Succeeded, JobProgressLogic.backup(BackupList(last_run = run("completed")), seenActive = true, unseenPolls = 0))
        assertEquals(JobStatus.Succeeded, JobProgressLogic.backup(BackupList(last_run = run("completed_with_warnings")), true, 0))
        assertEquals(JobStatus.Failed, JobProgressLogic.backup(BackupList(last_run = run("failed")), true, 0))
        assertEquals(JobStatus.Failed, JobProgressLogic.backup(BackupList(last_run = run("interrupted")), true, 0))
    }

    @Test fun aBackupNotListedYetIsStartingUntilTheMinuteIsUp() {
        assertEquals(JobStatus.Starting, JobProgressLogic.backup(BackupList(), seenActive = false, unseenPolls = 2))
        assertEquals(JobStatus.Gone, JobProgressLogic.backup(BackupList(), seenActive = false, unseenPolls = JobProgressLogic.MAX_UNSEEN_POLLS))
    }

    @Test fun anotherOperationHoldingTheLockIsNotThisBackup() {
        val restore = BackupList(active = active(10.0, operation = "restore"), busy = true)
        assertEquals(JobStatus.Starting, JobProgressLogic.backup(restore, seenActive = false, unseenPolls = 99))
    }

    private fun scan(library: String, status: String, at: String = "2026-11-15T10:00:00Z") =
        ScanRun(id = "$library-$status-$at", library_id = library, status = status, requested_at = at, completed_at = if (status == "running" || status == "accepted") null else at)

    @Test fun aScanIsStartingThenRunningThenDone() {
        assertEquals(JobStatus.Starting, JobProgressLogic.scan("a", listOf(scan("a", "accepted")), false, 0))
        assertEquals(JobStatus.Running(null), JobProgressLogic.scan("a", listOf(scan("a", "running")), false, 0))
        assertEquals(JobStatus.Succeeded, JobProgressLogic.scan("a", listOf(scan("a", "completed")), seenActive = true, unseenPolls = 0))
        assertEquals(JobStatus.Failed, JobProgressLogic.scan("a", listOf(scan("a", "failed")), seenActive = true, unseenPolls = 0))
    }

    @Test fun anotherLibrarysScanIsNotThisOne() {
        assertEquals(JobStatus.Starting, JobProgressLogic.scan("a", listOf(scan("b", "running")), false, 0))
        assertEquals(JobStatus.Gone, JobProgressLogic.scan("a", listOf(scan("b", "running")), false, JobProgressLogic.MAX_UNSEEN_POLLS))
    }

    @Test fun anOldFinishedRunIsNotReportedAsThisScan() {
        assertEquals(JobStatus.Starting, JobProgressLogic.scan("a", listOf(scan("a", "completed")), seenActive = false, unseenPolls = 1))
    }

    @Test fun onlyRunningAndStartingJobsKeepBeingPolled() {
        assertFalse(JobProgressLogic.isDone(JobStatus.Starting))
        assertFalse(JobProgressLogic.isDone(JobStatus.Running(5)))
        listOf(JobStatus.Succeeded, JobStatus.Failed, JobStatus.Gone).forEach { assertTrue(JobProgressLogic.isDone(it)) }
    }

    @Test fun theNotificationNamesTheLibraryOnlyInTheVersionShownOnceUnlocked() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val words = JobNotificationWords.build(context, JobKind.Scan, JobStatus.Running(null), "Home", "Films")
        assertTrue("Films" in words.title)
        assertFalse("Films" in words.publicTitle)
        assertFalse("Films" in words.publicText)
        assertFalse("Home" in words.publicTitle)
        assertFalse("Home" in words.publicText)
        assertTrue(words.ongoing)
        assertNull(words.percent)
    }

    @Test fun aBackupShowsItsPercentageAndAFinishedOneIsNoLongerOngoing() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val running = JobNotificationWords.build(context, JobKind.Backup, JobStatus.Running(63), "Home", null)
        assertEquals(63, running.percent)
        assertTrue(running.ongoing)
        assertTrue("63" in running.text)
        val done = JobNotificationWords.build(context, JobKind.Backup, JobStatus.Succeeded, "Home", null)
        assertFalse(done.ongoing)
        val failed = JobNotificationWords.build(context, JobKind.Backup, JobStatus.Failed, "Home", null)
        assertFalse(failed.ongoing)
        assertTrue(failed.publicTitle != done.publicTitle)
    }

    @Test fun aClientsAddressFindsItsSavedServer() {
        val servers = listOf(
            com.bloemstudios.admin.servers.AdminServer("a", "https://Home.test", "Home"),
            com.bloemstudios.admin.servers.AdminServer("b", "https://lab.test", "Lab"),
        )
        assertEquals("a", serverForOrigin(servers, "https://home.test")?.id)
        assertNull(serverForOrigin(servers, "https://other.test"))
    }
}
