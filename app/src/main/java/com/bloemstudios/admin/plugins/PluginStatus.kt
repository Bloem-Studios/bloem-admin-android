package com.bloemstudios.admin.plugins

import com.bloemstudios.admin.network.api.PluginAuthBinding
import com.bloemstudios.admin.network.api.PluginCapability
import kotlinx.serialization.Serializable

/**
 * An installed plugin as GET /api/v2/admin/plugins/installations sends it, reduced to what the Plugins page shows and acts
 * on. Configuration, bindings and the catalogue stay on the web.
 */
@Serializable
data class PluginInstallation(
    val id: String,
    val plugin_id: String,
    val version: String = "",
    val enabled: Boolean = true,
    val update_policy: String = "",
    val available_version: String? = null,
    val source_kind: String = "",
    val repository_id: String? = null,
    val repository_name: String? = null,
    val presentation: PluginPresentation? = null,
    val updates_paused: Boolean = false,
    val update_failure: PluginUpdateFailure? = null,
    val signature: PluginSignature = PluginSignature(),
    val runtime: PluginRuntime = PluginRuntime(),
    /** Read by the Settings sign-in and test actions, not by the Plugins page. */
    val capabilities: List<PluginCapability> = emptyList(),
    val auth_bindings: List<PluginAuthBinding> = emptyList(),
)

@Serializable
data class PluginPresentation(val display_name: String = "", val summary: String = "", val publisher_name: String = "")

/** The last automatic update Silo refused to install, written for the administrator. */
@Serializable
data class PluginUpdateFailure(val version: String = "", val reason: String = "")

/** The release signature check run at install: unknown, verified, unsigned or invalid. */
@Serializable
data class PluginSignature(val state: String = "unknown")

/** The plugin process: stopped, starting, running, backoff (restarting after a crash, resident plugins only) or failed. */
@Serializable
data class PluginRuntime(
    val resident: Boolean = false,
    val state: String = "stopped",
    val restart_count: Int = 0,
    val last_error: String? = null,
)

/** One plugin's state in a word. Off outranks everything: a plugin switched off is not failing, whatever its last run did. */
enum class PluginState { Running, Starting, Stopped, Restarting, Failed, Off }

fun pluginState(plugin: PluginInstallation): PluginState = when {
    !plugin.enabled -> PluginState.Off
    plugin.runtime.state == "failed" -> PluginState.Failed
    plugin.runtime.resident && plugin.runtime.state == "backoff" -> PluginState.Restarting
    plugin.runtime.state == "running" -> PluginState.Running
    plugin.runtime.state == "starting" -> PluginState.Starting
    else -> PluginState.Stopped
}

/**
 * The plugin needs an administrator: it is failing, restarting, its last automatic update was refused, or its release
 * signature failed the check. A plugin that is off never needs attention. (The web also flags a plugin missing required
 * settings; that check needs its settings form and stays on the web.)
 */
fun needsAttention(plugin: PluginInstallation): Boolean {
    if (!plugin.enabled) return false
    return when (pluginState(plugin)) {
        PluginState.Failed, PluginState.Restarting -> true
        else -> plugin.update_failure != null || plugin.signature.state == "invalid"
    }
}

/**
 * The newer version an administrator can install now. One recorded before the repository was removed or disabled, or for a
 * plugin installed outside any repository, cannot be applied, so it is not offered.
 */
fun pendingUpdate(plugin: PluginInstallation): String? {
    if (plugin.repository_id == null || plugin.updates_paused) return null
    return plugin.available_version?.takeIf { it.isNotBlank() }
}

/** Restarting a disabled plugin is refused (409), so it is offered only while the plugin is on. */
fun canRestart(plugin: PluginInstallation): Boolean = plugin.enabled

fun enabledAfterToggle(plugin: PluginInstallation): Boolean = !plugin.enabled

/** The plugin's display name, or one made from its id ("silo.tailscale-access" becomes "Tailscale Access"). */
fun pluginName(plugin: PluginInstallation): String {
    plugin.presentation?.display_name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val derived = plugin.plugin_id.replace(Regex("^silo[._-]?"), "")
        .split(Regex("[._-]+")).filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    return derived.ifEmpty { plugin.plugin_id }
}

enum class PluginFilter { All, NeedsAttention, UpdateAvailable, Off }

fun filtered(plugins: List<PluginInstallation>, filter: PluginFilter): List<PluginInstallation> = when (filter) {
    PluginFilter.All -> plugins
    PluginFilter.NeedsAttention -> plugins.filter(::needsAttention)
    PluginFilter.UpdateAvailable -> plugins.filter { pendingUpdate(it) != null }
    PluginFilter.Off -> plugins.filter { !it.enabled }
}

fun filterCounts(plugins: List<PluginInstallation>): Map<PluginFilter, Int> =
    PluginFilter.entries.associateWith { filtered(plugins, it).size }

private fun rank(plugin: PluginInstallation): Int = when {
    !plugin.enabled -> 4
    pluginState(plugin) == PluginState.Failed -> 0
    pluginState(plugin) == PluginState.Restarting -> 1
    needsAttention(plugin) -> 2
    else -> 3
}

/** Problems first, most severe first, then healthy plugins, then plugins that are off; each group by name. */
fun sortedForList(plugins: List<PluginInstallation>): List<PluginInstallation> =
    plugins.sortedWith(compareBy<PluginInstallation> { rank(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { pluginName(it) })

/** PUT /api/v2/admin/plugins/installations/{id} takes only the fields to change. */
@Serializable
data class PluginEnabledUpdate(val enabled: Boolean)
