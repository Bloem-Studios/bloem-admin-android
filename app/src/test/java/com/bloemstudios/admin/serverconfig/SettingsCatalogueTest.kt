package com.bloemstudios.admin.serverconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogueTest {
    // Text as the screens see it, by resource id: stands in for the string resources in a plain unit test.
    private val words = mapOf(
        "general" to "General", "playback" to "Playback", "logging" to "Logging", "email" to "Email",
        "server.log_level" to "Log level", "server.public_url" to "Public URL", "email.smtp_host" to "Mail server address",
        "playback.transcode_enabled" to "Video transcoding", "allow_4k_transcode" to "Allow 4K transcoding",
    )
    private fun labelOf(def: SettingDef) = words[def.key] ?: def.key
    private fun titleOf(page: SettingsPage) = words[page.id] ?: page.id

    @Test fun `no key is on two pages and every page has controls`() {
        val all = SETTINGS_PAGES.flatMap { it.settings.map(SettingDef::key) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(SETTINGS_PAGES.all { it.settings.isNotEmpty() })
    }

    @Test fun `number ranges are sound and choices have options`() {
        for (def in SETTINGS_PAGES.flatMap { it.settings }) {
            when (val kind = def.kind) {
                is SettingKind.Number -> assertTrue(def.key, kind.min == null || kind.max == null || kind.min <= kind.max)
                is SettingKind.Choice -> assertTrue(def.key, kind.options.size >= 2)
                else -> Unit
            }
        }
    }

    @Test fun `the pages a field admin needs are all there`() {
        val ids = SETTINGS_PAGES.map { it.id }
        assertEquals(listOf("general", "sign-in", "playback", "logging", "email", "notifications", "downloads"), ids)
    }

    @Test fun `keys still at their default are on the pages, as the effective settings report them`() {
        val general = SETTINGS_PAGES.first { it.id == "general" }
        assertTrue("server.public_url" in general.keys)
        assertTrue("server.log_level" in SETTINGS_PAGES.first { it.id == "logging" }.keys)
        assertTrue("playback.transcode_enabled" in SETTINGS_PAGES.first { it.id == "playback" }.keys)
    }

    @Test fun `a page shows only controls for keys the server reports`() {
        val logging = SETTINGS_PAGES.first { it.id == "logging" }
        val reported = setOf("server.log_level", "opslog.retention_days")
        val shown = logging.shownSettings(reported).map { it.key }
        assertEquals(listOf("server.log_level", "opslog.retention_days"), shown)
    }

    @Test fun `a page the server reports nothing for is hidden`() {
        val email = SETTINGS_PAGES.first { it.id == "email" }
        assertFalse(email.isAvailable(setOf("server.log_level")))
        assertTrue(email.isAvailable(setOf("email.enabled")))
    }

    @Test fun `controls with no server default follow the page once any of its keys is reported`() {
        val email = SETTINGS_PAGES.first { it.id == "email" }
        val shown = email.shownSettings(setOf("email.enabled", "email.smtp_port")).map { it.key }
        assertTrue("email.smtp_host" in shown)
        assertTrue("email.smtp_password" in shown)
    }

    @Test fun `keys outside the catalogue are the other settings`() {
        val others = otherSettingKeys(setOf("server.log_level", "scanner.workers", "tmdb.region"))
        assertEquals(listOf("scanner.workers", "tmdb.region"), others)
    }

    @Test fun `search finds one setting by its label and names its page`() {
        val hits = searchSettings("log level", SETTINGS_PAGES, ::titleOf, ::labelOf, reported = SETTINGS_PAGES.flatMap { it.keys }.toSet())
        assertEquals("server.log_level", hits.settings.first().def.key)
        assertEquals("logging", hits.settings.first().page.id)
    }

    @Test fun `search finds a setting by its key words`() {
        val hits = searchSettings("transcode enabled", SETTINGS_PAGES, ::titleOf, ::labelOf, reported = SETTINGS_PAGES.flatMap { it.keys }.toSet())
        assertTrue(hits.settings.any { it.def.key == "playback.transcode_enabled" })
    }

    @Test fun `search finds pages by the web's keywords`() {
        val hits = searchSettings("smtp", SETTINGS_PAGES, ::titleOf, ::labelOf, reported = SETTINGS_PAGES.flatMap { it.keys }.toSet())
        assertTrue(hits.pages.any { it.id == "email" })
    }

    @Test fun `search never returns controls the server does not report`() {
        val hits = searchSettings("log level", SETTINGS_PAGES, ::titleOf, ::labelOf, reported = setOf("email.enabled"))
        assertTrue(hits.settings.isEmpty())
    }

    @Test fun `a blank search finds nothing`() {
        val hits = searchSettings("  ", SETTINGS_PAGES, ::titleOf, ::labelOf, reported = setOf("server.log_level"))
        assertTrue(hits.pages.isEmpty() && hits.settings.isEmpty())
    }

    @Test fun `a restart reason finds the setting's label in the catalogue`() {
        assertNotNull(SETTINGS_PAGES.flatMap { it.settings }.firstOrNull { it.key == "server.public_url" })
        assertEquals("Public URL", labelForKey("server.public_url", ::labelOf))
        assertEquals(null, labelForKey("scanner.workers", ::labelOf))
    }
}
