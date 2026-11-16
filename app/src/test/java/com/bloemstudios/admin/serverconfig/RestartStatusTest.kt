package com.bloemstudios.admin.serverconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartStatusTest {
    private val labels = mapOf("playback.hw_accel" to "Hardware acceleration", "server.public_url" to "Public URL")

    @Test fun `a setting reason reads as the setting's name`() {
        val text = describeRestartReasons(listOf("setting:playback.hw_accel"), labels::get)
        assertEquals("Hardware acceleration", text)
    }

    @Test fun `several reasons are joined and repeated reasons are listed once`() {
        val text = describeRestartReasons(listOf("setting:playback.hw_accel", "setting:server.public_url", "setting:playback.hw_accel"), labels::get)
        assertEquals("Hardware acceleration, Public URL", text)
    }

    @Test fun `a setting outside the catalogue reads as plain words, never as the raw key`() {
        val text = describeRestartReasons(listOf("setting:scanner.max_concurrent_libraries"), labels::get)
        assertEquals("Scanner max concurrent libraries", text)
    }

    @Test fun `other reasons are humanized`() {
        assertEquals("Jellyfin compat", describeRestartReasons(listOf("jellyfin_compat"), labels::get))
    }

    @Test fun `no reasons gives no text`() {
        assertEquals(null, describeRestartReasons(emptyList(), labels::get))
    }

    @Test fun `status reasons prefer the list and fall back to the single reason`() {
        val both = ServerStatus("t", true, true, restart_required_reason = "old", restart_required_reasons = listOf("setting:server.public_url"))
        assertEquals(listOf("setting:server.public_url"), both.allRestartReasons)
        val single = ServerStatus("t", true, true, restart_required_reason = "old")
        assertEquals(listOf("old"), single.allRestartReasons)
    }

    @Test fun `restart keys match exact keys and prefixes`() {
        val keys = RestartKeys(keys = listOf("playback.hw_accel"), prefixes = listOf("s3."))
        assertTrue(keys.requiresRestart("playback.hw_accel"))
        assertTrue(keys.requiresRestart("s3.public_bucket"))
        assertFalse(keys.requiresRestart("server.log_level"))
    }
}
