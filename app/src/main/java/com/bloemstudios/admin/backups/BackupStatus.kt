package com.bloemstudios.admin.backups

import com.bloemstudios.admin.jobs.Elapsed
import com.bloemstudios.admin.jobs.elapsedOf
import com.bloemstudios.admin.jobs.isValidTimeOfDay
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** What GET /api/v2/admin/backups/capabilities says: `state` is available, or not_configured when the server has no storage for backups. */
@Serializable
data class BackupCapabilities(val state: String = "", val allowed: Boolean = true)

/** GET /api/v2/admin/backups: this server's backups, newest first, with the lock, the last run and the warnings the web shows. */
@Serializable
data class BackupList(
    val destination: String = "default",
    val destination_available: Boolean = true,
    val encrypted: Boolean = false,
    val key_confirmed: Boolean = true,
    val enabled: Boolean = false,
    /** A backup, delete or restore holds the cluster-wide backup lock now. */
    val busy: Boolean = false,
    val active: BackupActive? = null,
    val last_run: BackupLastRun? = null,
    val keep: Int = 0,
    val last_success_at: String? = null,
    val items: List<BackupItem> = emptyList(),
    val warnings: List<BackupWarning> = emptyList(),
)

/** The operation holding the lock. [progress] is a percentage and means something for a backup only. */
@Serializable
data class BackupActive(
    val operation: String,
    val backup_id: String? = null,
    val started_at: String = "",
    val progress: Double = 0.0,
    val message: String? = null,
)

@Serializable
data class BackupLastRun(
    val id: String,
    val kind: String,
    val status: String,
    val started_at: String,
    val finished_at: String? = null,
    val error: String? = null,
)

@Serializable
data class BackupWarning(val code: String, val message: String = "")

@Serializable
data class BackupItem(
    val id: String,
    val kind: String,
    val status: String,
    val created_at: String,
    val completed_at: String? = null,
    val size_bytes: Long = 0,
    val location: String = "",
    val encrypted: Boolean = false,
    val key_matches: Boolean = true,
    val clean: Boolean = false,
)

/** The 202 answer of POST /api/v2/admin/backups. */
@Serializable
data class BackupStarted(val id: String = "", val kind: String = "manual", val status: String = "in_progress")

/** Why Back up now is unavailable, in the order the web checks. */
sealed interface BackUpBlock {
    data object NoStorage : BackUpBlock
    data class Message(val text: String) : BackUpBlock
    data object KeyNotConfirmed : BackUpBlock
    /** Another operation holds the backup lock; [operation] is backup, delete, restore or upload when the server names it. */
    data class Busy(val operation: String?) : BackUpBlock
    data object DestinationUnavailable : BackUpBlock
}

fun backUpNowBlock(list: BackupList, configured: Boolean): BackUpBlock? {
    if (!configured) return BackUpBlock.NoStorage
    list.warnings.firstOrNull { it.code == "sqlite_user_store" }?.let { return BackUpBlock.Message(it.message) }
    if (list.warnings.any { it.code == "key_not_confirmed" }) return BackUpBlock.KeyNotConfirmed
    if (list.busy || list.active != null) return BackUpBlock.Busy(list.active?.operation)
    if (!list.destination_available) return BackUpBlock.DestinationUnavailable
    return null
}

/** The bar of a running backup, 0 to 1. A restore, delete or upload holding the lock has no percentage for a backup bar. */
fun runningBackupFraction(list: BackupList): Float? {
    val active = list.active?.takeIf { it.operation == "backup" } ?: return null
    if (!active.progress.isFinite()) return 0f
    return (active.progress / 100.0).coerceIn(0.0, 1.0).toFloat()
}

fun lastSuccessAge(list: BackupList, now: Instant): Elapsed? {
    val at = list.last_success_at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return elapsedOf(Duration.between(at, now).seconds)
}

/** A run that failed or was interrupted is a failure the page says in red; one that finished with warnings is not. */
fun lastRunProblem(run: BackupLastRun?): Boolean = run != null && (run.status == "failed" || run.status == "interrupted")

fun hasWarning(list: BackupList, code: String): Boolean = list.warnings.any { it.code == code }

/** Delete is refused while the lock is held, and a backup still being written cannot be removed. */
fun canDelete(item: BackupItem, list: BackupList): Boolean = !list.busy && item.status != "in_progress"

/** 1536 becomes "1.5 KB". */
fun sizeText(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (unit == 0) 0 else 1; minimumFractionDigits = 0; roundingMode = java.math.RoundingMode.HALF_UP }
    return "${format.format(value)} ${units[unit]}"
}

// ---- Settings: backup.enabled, backup.time and backup.keep -------------------------------------------------------

const val BACKUP_ENABLED_KEY = "backup.enabled"
const val BACKUP_TIME_KEY = "backup.time"
const val BACKUP_KEEP_KEY = "backup.keep"
const val MIN_BACKUP_KEEP = 1
const val MAX_BACKUP_KEEP = 60
private const val DEFAULT_BACKUP_TIME = "04:00"
private const val DEFAULT_BACKUP_KEEP = 7

/** The saved values. The server fills in these defaults for a key never set. */
data class BackupSettings(val enabled: Boolean, val time: String, val keep: Int) {
    fun toDraft() = BackupSettingsDraft(enabled, time, keep.toString())
}

fun backupSettingsOf(values: Map<String, String>): BackupSettings = BackupSettings(
    enabled = values[BACKUP_ENABLED_KEY]?.trim()?.lowercase()?.let { it in setOf("true", "1", "yes", "on") } ?: true,
    time = values[BACKUP_TIME_KEY]?.takeIf { isValidTimeOfDay(it) } ?: DEFAULT_BACKUP_TIME,
    keep = values[BACKUP_KEEP_KEY]?.trim()?.toIntOrNull()?.takeIf { it in MIN_BACKUP_KEEP..MAX_BACKUP_KEEP } ?: DEFAULT_BACKUP_KEEP,
)

/** What the editor holds: the time as typed (HH:MM on the server's clock) and the keep count as typed. */
data class BackupSettingsDraft(val enabled: Boolean, val time: String, val keep: String) {
    val keepCount: Int? get() = keep.trim().toIntOrNull()?.takeIf { it in MIN_BACKUP_KEEP..MAX_BACKUP_KEEP }
    val isValid: Boolean get() = isValidTimeOfDay(time) && keepCount != null
}

/** The keys whose value differs from the saved settings, as the server stores them. Empty for an invalid draft's untouched parts. */
fun changedSettings(saved: BackupSettings, draft: BackupSettingsDraft): Map<String, String> = buildMap {
    if (draft.enabled != saved.enabled) put(BACKUP_ENABLED_KEY, draft.enabled.toString())
    if (draft.time != saved.time) put(BACKUP_TIME_KEY, draft.time)
    draft.keepCount?.let { if (it != saved.keep) put(BACKUP_KEEP_KEY, it.toString()) }
}
