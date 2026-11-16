package com.bloemstudios.admin.automation

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.getBackupCapabilities
import com.bloemstudios.admin.network.api.getNodeCapabilities
import com.bloemstudios.admin.network.api.probePluginInstallations
import kotlinx.coroutines.CancellationException

/**
 * Which of the Nodes, Backups and Plugins pages this server can serve. Each is hidden unless the server says it can: an
 * older server answers 404 for the route, and any other doubt (a failed read) also leaves the page out rather than
 * offering one that would not load.
 */
data class AutomationPages(val nodes: Boolean = false, val backups: Boolean = false, val plugins: Boolean = false) {
    companion object { val None = AutomationPages() }
}

suspend fun ApiClient.readAutomationPages(): AutomationPages = AutomationPages(
    nodes = attempt { getNodeCapabilities().let { it.summary && it.allowed && it.state == "available" } },
    // not_configured still gets the page: it says what is missing and where to set it up.
    backups = attempt { getBackupCapabilities().let { it.allowed && (it.state == "available" || it.state == "not_configured") } },
    plugins = attempt { probePluginInstallations(); true },
)

private suspend fun attempt(read: suspend () -> Boolean): Boolean = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
