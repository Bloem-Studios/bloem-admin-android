package com.bloemstudios.admin.serverconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    private val base = mapOf("server.log_level" to "info", "playback.transcode_enabled" to "true", "branding.server_name" to "Home")

    @Test fun `an untouched draft reads the server values and has nothing to save`() {
        val draft = SettingsDraft(base)
        assertEquals("info", draft.value("server.log_level"))
        assertTrue(draft.dirtyKeys.isEmpty())
        assertFalse(draft.isDirty("server.log_level"))
    }

    @Test fun `an edit is dirty and an edit back to the server value is not`() {
        val edited = SettingsDraft(base).set("server.log_level", "debug")
        assertEquals("debug", edited.value("server.log_level"))
        assertEquals(setOf("server.log_level"), edited.dirtyKeys)
        val reverted = edited.set("server.log_level", "info")
        assertTrue(reverted.dirtyKeys.isEmpty())
    }

    @Test fun `a key the server does not report reads as empty`() {
        assertEquals("", SettingsDraft(base).value("email.smtp_host"))
        assertTrue(SettingsDraft(base).set("email.smtp_host", "smtp.example.test").isDirty("email.smtp_host"))
    }

    @Test fun `discard drops every edit`() {
        val draft = SettingsDraft(base).set("server.log_level", "debug").set("branding.server_name", "Cabin")
        assertTrue(draft.discard().dirtyKeys.isEmpty())
    }

    @Test fun `rebase keeps the edits that still differ from the new server values`() {
        val draft = SettingsDraft(base).set("server.log_level", "debug").set("branding.server_name", "Cabin")
        // Someone else already set the name to Cabin and changed the transcode switch.
        val rebased = draft.rebase(base + mapOf("branding.server_name" to "Cabin", "playback.transcode_enabled" to "false"))
        assertEquals(setOf("server.log_level"), rebased.dirtyKeys)
        assertEquals("debug", rebased.value("server.log_level"))
        assertEquals("false", rebased.value("playback.transcode_enabled"))
    }

    @Test fun `saved keys leave the draft and the receipt values become the server values`() {
        val draft = SettingsDraft(base).set("server.log_level", "debug").set("branding.server_name", "Cabin")
        val saved = draft.saved(setOf("server.log_level"), mapOf("server.log_level" to "debug"))
        assertEquals(setOf("branding.server_name"), saved.dirtyKeys)
        assertEquals("debug", saved.base["server.log_level"])
    }

    @Test fun `only the given keys are offered for saving`() {
        val draft = SettingsDraft(base).set("server.log_level", "debug").set("branding.server_name", "Cabin")
        assertEquals(mapOf("server.log_level" to "debug"), draft.changes(setOf("server.log_level", "email.smtp_host")))
    }

    @Test fun `number validation follows the documented range`() {
        val kind = SettingKind.Number(min = 1, max = 30)
        assertNull(kind.problem("7"))
        assertEquals(NumberProblem.NotANumber, kind.problem("abc"))
        assertEquals(NumberProblem.NotANumber, kind.problem(""))
        assertEquals(NumberProblem.TooLow, kind.problem("0"))
        assertEquals(NumberProblem.TooHigh, kind.problem("31"))
        assertNull(SettingKind.Number().problem("5000000"))
    }
}
