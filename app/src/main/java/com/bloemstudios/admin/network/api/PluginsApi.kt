package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.plugins.PluginEnabledUpdate
import com.bloemstudios.admin.plugins.PluginInstallation
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject

private fun installationPath(id: String, suffix: String = "") = "/api/v2/admin/plugins/installations/${id.encodeURLPathPart()}$suffix"

/** Every installed plugin, read in full across pages. An older server that has no such route answers 404, so the page stays hidden. */
suspend fun ApiClient.listPluginInstallations(): List<PluginInstallation> = readAllPages("/api/v2/admin/plugins/installations")

/** Only the first plugin: the cheap read that tells whether this server has the Plugins page at all. */
suspend fun ApiClient.probePluginInstallations() {
    getCollection<JsonObject>("/api/v2/admin/plugins/installations", mapOf("limit" to "1"))
}

/** Stops the plugin's process and, for a resident plugin, starts it again with a fresh failure budget. A disabled plugin answers 409. */
suspend fun ApiClient.restartPlugin(id: String) = post(installationPath(id, "/restart"))

/** Turns a plugin on or off. Turning it off stops the running process first. */
suspend fun ApiClient.setPluginEnabled(id: String, enabled: Boolean) {
    put<JsonObject, PluginEnabledUpdate>(installationPath(id), PluginEnabledUpdate(enabled))
}

/** Installs the recorded available version from the plugin's repository. No applicable update answers 409. */
suspend fun ApiClient.updatePlugin(id: String) = post(installationPath(id, "/update"))
