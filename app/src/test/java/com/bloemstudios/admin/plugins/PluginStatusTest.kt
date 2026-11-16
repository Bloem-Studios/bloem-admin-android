package com.bloemstudios.admin.plugins

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStatusTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun plugin(
        id: String = "1",
        pluginId: String = "silo.tailscale-access",
        enabled: Boolean = true,
        runtimeState: String = "running",
        resident: Boolean = true,
        repositoryId: String? = "9",
        available: String? = null,
        updatesPaused: Boolean = false,
        updateFailure: PluginUpdateFailure? = null,
        signature: String = "verified",
        displayName: String? = null,
    ) = PluginInstallation(
        id = id, plugin_id = pluginId, version = "1.2.0", enabled = enabled, update_policy = "notify", source_kind = "silo",
        repository_id = repositoryId, available_version = available, updates_paused = updatesPaused, update_failure = updateFailure,
        signature = PluginSignature(signature), runtime = PluginRuntime(resident = resident, state = runtimeState),
        presentation = displayName?.let { PluginPresentation(display_name = it) },
    )

    @Test fun `the installation decodes from the server's row`() {
        val raw = """{"id":"4","repository_id":"2","plugin_id":"silo.oidc","version":"0.9.1","install_path":"/p","enabled":true,"kind":"auth","update_policy":"auto","available_version":"0.9.2","source_kind":"approved_community","repository_name":"Community","updates_paused":false,
            "presentation":{"display_name":"OpenID Connect","summary":"Sign in with OIDC","publisher_name":"Silo"},"signature":{"state":"invalid"},
            "update_failure":{"version":"0.9.2","reason":"The download did not match its checksum","failed_at":"2026-11-13T04:00:00.000Z"},
            "capabilities":[],"runtime":{"resident":false,"state":"failed","restart_count":3,"last_error":"exit status 1"},"created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}"""
        val decoded = json.decodeFromString<PluginInstallation>(raw)
        assertEquals("OpenID Connect", decoded.presentation?.display_name)
        assertEquals("failed", decoded.runtime.state)
        assertEquals("exit status 1", decoded.runtime.last_error)
        assertEquals("invalid", decoded.signature.state)
        assertEquals("0.9.2", decoded.update_failure?.version)
    }

    @Test fun `a plugin without a display name is named from its id`() {
        assertEquals("Tailscale Access", pluginName(plugin()))
        assertEquals("OpenID Connect", pluginName(plugin(displayName = "OpenID Connect")))
        assertEquals("Ldap", pluginName(plugin(pluginId = "silo-ldap")))
        assertEquals("silo", pluginName(plugin(pluginId = "silo")))
    }

    @Test fun `a failed or backing off plugin needs attention, and an off plugin never does`() {
        assertEquals(PluginState.Failed, pluginState(plugin(runtimeState = "failed")))
        assertTrue(needsAttention(plugin(runtimeState = "failed")))
        assertEquals(PluginState.Restarting, pluginState(plugin(runtimeState = "backoff")))
        assertTrue(needsAttention(plugin(runtimeState = "backoff")))
        assertFalse(needsAttention(plugin(runtimeState = "backoff", resident = false)))
        assertEquals(PluginState.Off, pluginState(plugin(enabled = false, runtimeState = "failed")))
        assertFalse(needsAttention(plugin(enabled = false, runtimeState = "failed")))
    }

    @Test fun `a refused update or an invalid signature needs attention`() {
        assertTrue(needsAttention(plugin(updateFailure = PluginUpdateFailure("1.3.0", "Checksum mismatch"))))
        assertTrue(needsAttention(plugin(signature = "invalid")))
        assertFalse(needsAttention(plugin(signature = "unsigned")))
        assertFalse(needsAttention(plugin()))
    }

    @Test fun `running, starting and stopped read as themselves`() {
        assertEquals(PluginState.Running, pluginState(plugin(runtimeState = "running")))
        assertEquals(PluginState.Starting, pluginState(plugin(runtimeState = "starting")))
        assertEquals(PluginState.Stopped, pluginState(plugin(runtimeState = "stopped")))
        assertEquals(PluginState.Stopped, pluginState(plugin(runtimeState = "something-new")))
    }

    @Test fun `an update is offered only for a plugin from a repository that is not paused`() {
        assertEquals("1.3.0", pendingUpdate(plugin(available = "1.3.0")))
        assertNull(pendingUpdate(plugin(available = "1.3.0", repositoryId = null)))
        assertNull(pendingUpdate(plugin(available = "1.3.0", updatesPaused = true)))
        assertNull(pendingUpdate(plugin(available = "")))
        assertNull(pendingUpdate(plugin()))
    }

    @Test fun `Restart is for an enabled plugin and On or Off follows the plugin's own switch`() {
        assertTrue(canRestart(plugin()))
        assertFalse(canRestart(plugin(enabled = false)))
        assertEquals(false, enabledAfterToggle(plugin(enabled = true)))
        assertEquals(true, enabledAfterToggle(plugin(enabled = false)))
    }

    @Test fun `the filters pick the plugins they name`() {
        val ok = plugin(id = "1")
        val failing = plugin(id = "2", runtimeState = "failed")
        val updatable = plugin(id = "3", available = "2.0.0")
        val off = plugin(id = "4", enabled = false)
        val all = listOf(ok, failing, updatable, off)
        assertEquals(listOf("1", "2", "3", "4"), filtered(all, PluginFilter.All).map { it.id })
        assertEquals(listOf("2"), filtered(all, PluginFilter.NeedsAttention).map { it.id })
        assertEquals(listOf("3"), filtered(all, PluginFilter.UpdateAvailable).map { it.id })
        assertEquals(listOf("4"), filtered(all, PluginFilter.Off).map { it.id })
    }

    @Test fun `problems sort first, then healthy plugins, then off plugins, each by name`() {
        val failing = plugin(id = "f", pluginId = "silo.zeta", runtimeState = "failed")
        val backoff = plugin(id = "b", pluginId = "silo.alpha", runtimeState = "backoff")
        val healthyB = plugin(id = "h2", pluginId = "silo.beta")
        val healthyA = plugin(id = "h1", pluginId = "silo.aaa")
        val off = plugin(id = "o", pluginId = "silo.aardvark", enabled = false)
        assertEquals(listOf("f", "b", "h1", "h2", "o"), sortedForList(listOf(off, healthyB, backoff, healthyA, failing)).map { it.id })
    }

    @Test fun `the counts for the filter chips`() {
        val all = listOf(plugin(id = "1"), plugin(id = "2", runtimeState = "failed"), plugin(id = "3", enabled = false))
        assertEquals(mapOf(PluginFilter.All to 3, PluginFilter.NeedsAttention to 1, PluginFilter.UpdateAvailable to 0, PluginFilter.Off to 1), filterCounts(all))
    }
}
