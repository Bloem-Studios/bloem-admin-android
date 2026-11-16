package com.bloemstudios.admin.backups

import com.bloemstudios.admin.jobs.Elapsed
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupStatusTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun list(
        busy: Boolean = false,
        active: BackupActive? = null,
        destinationAvailable: Boolean = true,
        warnings: List<BackupWarning> = emptyList(),
        lastSuccessAt: String? = "2026-11-14T04:03:00.000Z",
        lastRun: BackupLastRun? = null,
        enabled: Boolean = true,
        keep: Int = 7,
        items: List<BackupItem> = emptyList(),
    ) = BackupList(
        destination = "default", destination_available = destinationAvailable, enabled = enabled, busy = busy, active = active, last_run = lastRun,
        keep = keep, last_success_at = lastSuccessAt, items = items, warnings = warnings,
    )

    @Test fun `the list decodes with the fields the page reads`() {
        val raw = """{"server_id":"s","destination":"default","destination_available":true,"encrypted":false,"key_check":"abcd","key_confirmed":true,"enabled":true,"busy":true,
            "active":{"operation":"backup","started_at":"2026-11-14T10:00:00.000Z","progress":42.5,"message":"Copying files"},
            "last_run":{"id":"r","kind":"nightly","status":"failed","started_at":"2026-11-13T04:00:00.000Z","error":"No space left"},
            "keep":7,"last_success_at":"2026-11-12T04:03:00.000Z",
            "items":[{"id":"20261112T040000Z-nightly","kind":"nightly","status":"complete","created_at":"2026-11-12T04:00:00.000Z","completed_at":"2026-11-12T04:03:00.000Z","size_bytes":1048576,"location":"/data/backups","blob_count":3,"missing_blobs":[],"missing_blobs_total":0,"restore_set_omitted":[],"objects":[],"encrypted":false,"key_matches":true,"clean":true}],
            "other_server_ids":[],"warnings":[{"code":"no_recent_backup","message":"No backup in 48 hours"}]}"""
        val decoded = json.decodeFromString<BackupList>(raw)
        assertEquals(42.5, decoded.active!!.progress, 0.0)
        assertEquals("No space left", decoded.last_run!!.error)
        assertEquals(1_048_576L, decoded.items.single().size_bytes)
        assertEquals("no_recent_backup", decoded.warnings.single().code)
    }

    @Test fun `Back up now is blocked in the web's order`() {
        assertEquals(BackUpBlock.NoStorage, backUpNowBlock(list(), configured = false))
        val sqlite = BackupWarning("sqlite_user_store", "Backups need PostgreSQL")
        assertEquals(BackUpBlock.Message("Backups need PostgreSQL"), backUpNowBlock(list(warnings = listOf(sqlite)), configured = true))
        val key = BackupWarning("key_not_confirmed", "Confirm the key")
        assertEquals(BackUpBlock.KeyNotConfirmed, backUpNowBlock(list(warnings = listOf(key), busy = true), configured = true))
        assertEquals(BackUpBlock.Busy("backup"), backUpNowBlock(list(busy = true, active = BackupActive("backup", progress = 10.0)), configured = true))
        assertEquals(BackUpBlock.Busy(null), backUpNowBlock(list(busy = true), configured = true))
        assertEquals(BackUpBlock.DestinationUnavailable, backUpNowBlock(list(destinationAvailable = false), configured = true))
        assertNull(backUpNowBlock(list(), configured = true))
    }

    @Test fun `a running backup shows its progress and nothing else shows a bar`() {
        assertEquals(0.425f, runningBackupFraction(list(active = BackupActive("backup", progress = 42.5)))!!, 0.0001f)
        assertNull(runningBackupFraction(list(active = BackupActive("restore", progress = 42.5))))
        assertNull(runningBackupFraction(list()))
        assertEquals(1f, runningBackupFraction(list(active = BackupActive("backup", progress = 300.0)))!!, 0f)
    }

    @Test fun `the last success is aged and a server that never succeeded says so`() {
        assertEquals(Elapsed.Hours(5), lastSuccessAge(list(), now))
        assertNull(lastSuccessAge(list(lastSuccessAt = null), now))
    }

    @Test fun `the last run is a problem when it failed or was interrupted`() {
        assertTrue(lastRunProblem(BackupLastRun("r", "nightly", "failed", "2026-11-13T04:00:00Z", error = "Disk full")))
        assertTrue(lastRunProblem(BackupLastRun("r", "nightly", "interrupted", "2026-11-13T04:00:00Z")))
        assertFalse(lastRunProblem(BackupLastRun("r", "nightly", "complete", "2026-11-13T04:00:00Z")))
        assertFalse(lastRunProblem(BackupLastRun("r", "nightly", "complete_with_warnings", "2026-11-13T04:00:00Z")))
        assertFalse(lastRunProblem(null))
    }

    @Test fun `no recent backup is a warning the page shows in the warning color`() {
        assertTrue(hasWarning(list(warnings = listOf(BackupWarning("no_recent_backup", "m"))), "no_recent_backup"))
        assertFalse(hasWarning(list(), "no_recent_backup"))
    }

    @Test fun `a saved backup can be deleted unless it is being written or the lock is held`() {
        val done = BackupItem(id = "a", kind = "nightly", status = "complete", created_at = "2026-11-12T04:00:00Z")
        val writing = done.copy(id = "b", status = "in_progress")
        assertTrue(canDelete(done, list()))
        assertFalse(canDelete(writing, list()))
        assertFalse(canDelete(done, list(busy = true)))
    }

    @Test fun `backup sizes are shown in the unit that reads best`() {
        assertEquals("512 B", sizeText(512, java.util.Locale.ENGLISH))
        assertEquals("1.5 KB", sizeText(1_536, java.util.Locale.ENGLISH))
        assertEquals("1 MB", sizeText(1_048_576, java.util.Locale.ENGLISH))
        assertEquals("2.3 GB", sizeText((2.25 * 1024 * 1024 * 1024).toLong(), java.util.Locale.ENGLISH))
    }

    // ---- the settings draft -------------------------------------------------------------------------------------

    private val original = BackupSettings(enabled = true, time = "04:00", keep = 7)

    @Test fun `settings are read from the effective settings, with the server's defaults when a key is missing`() {
        assertEquals(BackupSettings(false, "03:30", 14), backupSettingsOf(mapOf("backup.enabled" to "false", "backup.time" to "03:30", "backup.keep" to "14")))
        assertEquals(BackupSettings(true, "04:00", 7), backupSettingsOf(emptyMap()))
    }

    @Test fun `a draft is valid with a 24 hour time and a keep count from 1 to 60`() {
        assertTrue(BackupSettingsDraft(true, "04:00", "7").isValid)
        assertTrue(BackupSettingsDraft(false, "23:59", "60").isValid)
        assertFalse(BackupSettingsDraft(true, "24:00", "7").isValid)
        assertFalse(BackupSettingsDraft(true, "4:00", "7").isValid)
        assertFalse(BackupSettingsDraft(true, "04:00", "0").isValid)
        assertFalse(BackupSettingsDraft(true, "04:00", "61").isValid)
        assertFalse(BackupSettingsDraft(true, "04:00", "").isValid)
    }

    @Test fun `only the changed keys are written, with the server's value format`() {
        assertEquals(emptyMap<String, String>(), changedSettings(original, BackupSettingsDraft(true, "04:00", "7")))
        assertEquals(mapOf("backup.enabled" to "false"), changedSettings(original, BackupSettingsDraft(false, "04:00", "7")))
        assertEquals(
            mapOf("backup.time" to "02:15", "backup.keep" to "21"),
            changedSettings(original, BackupSettingsDraft(true, "02:15", "21")),
        )
    }

    @Test fun `a draft starts from the saved settings`() {
        assertEquals(BackupSettingsDraft(true, "04:00", "7"), original.toDraft())
    }
}
