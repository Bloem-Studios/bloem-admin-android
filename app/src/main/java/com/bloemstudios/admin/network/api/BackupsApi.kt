package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.backups.BackupCapabilities
import com.bloemstudios.admin.backups.BackupList
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.AppDataSignal
import io.ktor.http.encodeURLPathPart

/** Whether this server can back up at all: `state` is available, or not_configured when it has no storage for backups. */
suspend fun ApiClient.getBackupCapabilities(): BackupCapabilities = get("/api/v2/admin/backups/capabilities")

/** This server's backups, newest first, with the lock, the last run and the warnings. */
suspend fun ApiClient.listBackups(): BackupList = get("/api/v2/admin/backups")

/** Back up now. The backup runs on the server and answers 202 at once; a running or conflicting operation answers 409. */
suspend fun ApiClient.startBackup() {
    post("/api/v2/admin/backups")
    AppDataSignal.emit(AppDataSignal.BackupStarted(origin.issuer))
}

/** Deletes one saved backup. Refused (409) while the backup lock is held or a restore reads it. */
suspend fun ApiClient.deleteBackup(id: String) = delete("/api/v2/admin/backups/${id.encodeURLPathPart()}")
